#!/usr/bin/env bash
# 魅影TV 发版脚本：Gitee Release 附件 + 双平台代码推送 + 清单镜像复查
#
# 用法（在仓库根目录执行）：
#   GITEE_TOKEN=你的令牌 ./release/publish.sh 0.5.3 "本次更新说明"
#   ./release/publish.sh --verify-only     # 只复查镜像清单，不发布（自测/事后补救用）
#
# 前提：APK 已编译并复制到 release/apk/iptv-player-<版本>.apk
# 令牌只从环境变量读，不写进任何文件；用后可从 Gitee 删除重建。

set -euo pipefail

OWNER="lzp-zl"
REPO="meiying-tv"
GH_OWNER="lzp1314"
GH_REPO="meiying-tv"

# --- curl 的证书吊销检查 ---------------------------------------------------
# Windows 自带 curl 走 schannel，国内网络连不上吊销服务器会直接判失败
# （0x80092012 CRYPT_E_REVOCATION_OFFLINE，表现为 HTTP 000）。发版机上加
# --ssl-no-revoke 跳过这一步即可；Linux/macOS 的 curl 没有该开关，探测后再决定。
CURL_SSL=""
if curl --help all >/dev/null 2>&1; then CURL_HELP=$(curl --help all); else CURL_HELP=$(curl --help); fi
printf '%s' "$CURL_HELP" | grep -q -- "--ssl-no-revoke" && CURL_SSL="--ssl-no-revoke"

# 客户端 UpdateChecker.MIRRORS 里的三个地址，改一处必须改两处
M_GITEE="https://gitee.com/${OWNER}/${REPO}/raw/master/release/update.json"
M_RAW="https://raw.githubusercontent.com/${GH_OWNER}/${GH_REPO}/master/release/update.json"
M_JS="https://fastly.jsdelivr.net/gh/${GH_OWNER}/${GH_REPO}@master/release/update.json"
PURGE_JS="https://purge.jsdelivr.net/gh/${GH_OWNER}/${GH_REPO}@master/release/update.json"

EXPECT_CODE=""
EXPECT_NAME=""
EXPECT_SHA=""

# 从本地清单读期望值（版本号以 build.gradle.kts 为准，这里只是复核）
# 注意 tr -d '\r'：Windows 下 Python 的 stdout 会把 \n 转成 \r\n，不清理的话
# 最后一个字段会粘一个回车，比较永远不相等（表现为主通道明明更新了却判失败）。
load_expect() {
  local code name sha line
  line=$(python -c "
import json
d = json.load(open('release/update.json', encoding='utf-8'))
print(d['versionCode'], d['versionName'], (d.get('sha256') or '')[:16])
" | tr -d '\r')
  read -r code name sha <<< "$line"
  EXPECT_CODE="$code"; EXPECT_NAME="$name"; EXPECT_SHA="$sha"
}

# 拉一份清单，输出 "code|name|sha16|错误片段"
mirror_state() {
  local body
  body=$(curl -sL $CURL_SSL --max-time 18 "$1" 2>/dev/null || true)
  printf '%s' "$body" | python -c "
import sys, json
raw = sys.stdin.read()
try:
    d = json.loads(raw)
    print('%s|%s|%s|' % (d.get('versionCode'), d.get('versionName'), (d.get('sha256') or '')[:16]))
except Exception:
    print('|||' + (raw[:40].replace('\n', ' ') or 'empty'))
" | tr -d '\r'
}

is_expected() {
  local s="$1" code name sha rest
  IFS='|' read -r code name sha rest <<< "$s"
  [ "$code" = "$EXPECT_CODE" ] || return 1
  [ "$name" = "$EXPECT_NAME" ] || return 1
  if [ -n "$EXPECT_SHA" ] && [ "$sha" != "$EXPECT_SHA" ]; then return 1; fi
  return 0
}

purge_jsdelivr() {
  local r
  r=$(curl -sL $CURL_SSL --max-time 25 "$PURGE_JS" 2>/dev/null || true)
  printf '%s' "$r" | python -c "
import sys, json
try:
    d = json.load(sys.stdin)
    paths = d.get('paths', {})
    ok = bool(paths) and all(v.get('providers') for v in paths.values())
    print('   purge: %s%s' % (d.get('status'), ' / providers 全清' if ok else ' / providers 未全清'))
except Exception:
    print('   purge: 无响应（忽略，继续复查）')
"
}

# 复查三个镜像：主通道不一致就算发版失败，jsDelivr 只告警（分支缓存不完全可控）
verify_mirrors() {
  local round s_g='' s_r='' s_j='' ok_g=0 ok_r=0 ok_j=0
  echo ""
  echo "=== 清单镜像复查（期望 vc${EXPECT_CODE} / ${EXPECT_NAME}）==="
  for round in 1 2 3 4 5 6; do
    case "$round" in
      1|3) purge_jsdelivr ;;
    esac
    if [ "$ok_g" = 0 ]; then s_g=$(mirror_state "$M_GITEE"); is_expected "$s_g" && ok_g=1 || true; fi
    if [ "$ok_r" = 0 ]; then s_r=$(mirror_state "$M_RAW");   is_expected "$s_r" && ok_r=1 || true; fi
    if [ "$ok_j" = 0 ]; then s_j=$(mirror_state "$M_JS");    is_expected "$s_j" && ok_j=1 || true; fi

    if [ "$ok_g" = 1 ] && [ "$ok_r" = 1 ] && [ "$ok_j" = 1 ]; then
      echo "   第 ${round} 轮全部就绪"
      break
    fi
    if [ "$round" -lt 6 ]; then
      echo "   第 ${round} 轮未全部就绪（gitee=$ok_g raw=$ok_r jsdelivr=$ok_j），10 秒后重试"
      sleep 10
    fi
  done

  report() {
    local mark="❌"
    [ "$3" = 1 ] && mark="✅"
    printf '  %s %-12s %s\n' "$mark" "$1" "${2:-无响应}"
  }
  report "Gitee raw"   "$s_g" "$ok_g"
  report "GitHub raw"  "$s_r" "$ok_r"
  report "jsDelivr"    "$s_j" "$ok_j"

  if [ "$ok_g" = 0 ]; then
    echo "❌ Gitee 主通道清单与发布版本不一致：先确认 git push 是否成功"
    return 1
  fi
  # 分阶段发布时 GitHub 还没推，raw 落后是预期的，不能判本次发布失败
  if [ "$ok_r" = 0 ] && [ "${SKIP_GH_PUSH:-0}" != "1" ]; then
    echo "❌ GitHub raw 清单与发布版本不一致：先确认 git push 是否成功、raw 是否有边缘缓存"
    return 1
  fi
  if [ "$ok_r" = 0 ]; then
    echo "⚠️ GitHub raw 尚未更新（分阶段发布，GitHub 还没推），不影响 Gitee 侧发布"
  fi
  if [ "$ok_j" = 0 ]; then
    echo "⚠️ jsDelivr 仍返回旧清单（分支缓存），不影响本次发布；"
    echo "   稍后重跑 ./release/publish.sh --verify-only 再确认，或等缓存自然过期。"
    return 0
  fi
  echo "✅ 三个镜像清单与发布版本一致"
}

# --- --verify-only：不发布，只复查 ---------------------------------------
if [ "${1:-}" = "--verify-only" ]; then
  [ -f release/update.json ] || { echo "找不到 release/update.json"; exit 1; }
  load_expect
  verify_mirrors
  exit $?
fi

VERSION="${1:?用法: GITEE_TOKEN=xxx ./release/publish.sh 0.5.3 \"更新说明\"}"
NOTES="${2:-魅影TV 更新}"
TAG="v${VERSION}"
APK="release/apk/iptv-player-${VERSION}.apk"
TOKEN="${GITEE_TOKEN:?请先设置环境变量 GITEE_TOKEN}"

[ -f "$APK" ] || { echo "找不到 $APK，请先编译并复制"; exit 1; }
echo "发布 $TAG -> $APK ($(wc -c < "$APK") bytes)"

# 1. 若同名 Release 已存在则删掉重建（避免 Gitee 同名 tag 冲突）
existing=$(curl -s $CURL_SSL "https://gitee.com/api/v5/repos/${OWNER}/${REPO}/releases?access_token=${TOKEN}" \
  | python -c "import sys,json; d=json.load(sys.stdin); print(next((r['id'] for r in d if r['tag_name']=='$TAG'), ''))" 2>/dev/null || true)
if [ -n "$existing" ]; then
  echo "删除旧 Release $existing"
  curl -s -X DELETE "https://gitee.com/api/v5/repos/${OWNER}/${REPO}/releases/${existing}?access_token=${TOKEN}" >/dev/null
  git push origin ":refs/tags/${TAG}" >/dev/null 2>&1 || true
fi

# 2. 建 Release（JSON + UTF-8，中文不乱码）
python - "$TOKEN" "$TAG" "$NOTES" <<'PY'
import json, sys, urllib.request
token, tag, notes = sys.argv[1:4]
data = {
    "access_token": token,
    "tag_name": tag,
    "name": "魅影TV " + tag,
    "body": notes,
    "target_commitish": "master",
    "prerelease": False,
}
req = urllib.request.Request(
    "https://gitee.com/api/v5/repos/lzp-zl/meiying-tv/releases",
    data=json.dumps(data).encode("utf-8"), method="POST",
    headers={"Content-Type": "application/json;charset=UTF-8"})
result = json.loads(urllib.request.urlopen(req, timeout=60).read().decode("utf-8"))
print("RELEASE_ID=" + str(result["id"]))
PY

REL_ID=$(python - "$TOKEN" "$TAG" <<'PY'
import json, sys, urllib.request
token, tag = sys.argv[1:3]
url = "https://gitee.com/api/v5/repos/lzp-zl/meiying-tv/releases?access_token=" + token
for r in json.loads(urllib.request.urlopen(url, timeout=30).read().decode("utf-8")):
    if r["tag_name"] == tag:
        print(r["id"]); break
PY
)
echo "Release ID: $REL_ID"

# 3. 上传 APK 附件
curl -s -X POST "https://gitee.com/api/v5/repos/${OWNER}/${REPO}/releases/${REL_ID}/attach_files" \
  -F "access_token=${TOKEN}" -F "file=@${APK}" | python -c \
  "import sys,json; d=json.load(sys.stdin); print('附件直链:', d['browser_download_url'])" > /tmp/meiying_apk_url.txt
APK_URL=$(grep 'http' /tmp/meiying_apk_url.txt | sed 's/附件直链: //')
cat /tmp/meiying_apk_url.txt

# 4. 写 update.json（版本从 build.gradle.kts 读，避免手写出错）
#    sha256 是更新通道的安全底座：客户端在证书校验被降级时靠它确认包没被换过。
#    apkFallback 指向 GitHub Release，Gitee 抽风时自动换路。
python - "$APK_URL" "$NOTES" "$VERSION" "$APK" <<'PY'
import json, sys, re, hashlib
url, notes, version, apk_path = sys.argv[1:5]
src = open("android/app/build.gradle.kts", encoding="utf-8").read()
code = int(re.search(r"versionCode\s*=\s*(\d+)", src).group(1))
name = re.search(r'versionName\s*=\s*"([^"]+)"', src).group(1)
h = hashlib.sha256()
with open(apk_path, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
fallback = ("https://github.com/lzp1314/meiying-tv/releases/download/v%s/iptv-player-%s.apk"
            % (version, version))
data = {
    "versionCode": code,
    "versionName": name,
    "apk": url,
    "apkFallback": [fallback],
    "sha256": h.hexdigest(),
    "notes": notes,
}
with open("release/update.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
print("update.json 已更新: vc%s / %s / sha256=%s" % (code, name, h.hexdigest()[:16]))
PY

# 5. 提交并推送（GitHub 默认分支是 main，master 也要同步，避免主页显示空仓库）
#    SKIP_GH_PUSH=1 时只推 Gitee——用于「先 Gitee 验完再推 GitHub」的分平台发布。
git add release/update.json
git commit -m "发布魅影TV ${TAG}" || echo "无改动可提交"
git push origin main:master
if [ "${SKIP_GH_PUSH:-0}" != "1" ]; then
  git push github main
  git push github main:master
else
  echo "SKIP_GH_PUSH=1：本次只推 Gitee，GitHub 留到下一阶段"
fi

# 6. 端到端验证：线上 update.json -> 匿名下载 -> MD5 + sha256 比对
ONLINE_JSON=$(curl -sL $CURL_SSL "https://gitee.com/${OWNER}/${REPO}/raw/master/release/update.json")
ONLINE=$(printf '%s' "$ONLINE_JSON" | python -c "import sys,json; print(json.load(sys.stdin)['apk'])")
ONLINE_SHA=$(printf '%s' "$ONLINE_JSON" | python -c "import sys,json; print(json.load(sys.stdin).get('sha256',''))")
echo "线上 apk: $ONLINE"
curl -sL $CURL_SSL --max-time 900 -o /tmp/meiying_verify.apk "$ONLINE"
LOCAL_MD5=$(md5sum "$APK" | cut -d' ' -f1)
REMOTE_MD5=$(md5sum /tmp/meiying_verify.apk | cut -d' ' -f1)
LOCAL_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] && echo "✅ 匿名下载可用，MD5 一致" || { echo "❌ MD5 不一致"; exit 1; }
if [ -n "$ONLINE_SHA" ]; then
  [ "$LOCAL_SHA" = "$ONLINE_SHA" ] && echo "✅ 清单里的 sha256 与本地包一致" || { echo "❌ sha256 不一致（清单写的和实际包不符）"; exit 1; }
else
  echo "⚠️ update.json 没有 sha256 字段"
fi

# 7. 清单镜像：先刷 jsDelivr 分支缓存，再延迟复查三个地址是否都已经是这一版
#    jsDelivr 对 @branch 的清单有小时级缓存，不清的话客户端在 Gitee 失败时会读到上一版，
#    表现就是「明明发版了，检查更新却说已是最新」。
load_expect
verify_mirrors
