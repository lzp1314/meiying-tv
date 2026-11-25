#!/usr/bin/env bash
# 魅影TV 发版脚本：Gitee Release 附件 + 双平台代码推送
#
# 用法（在仓库根目录执行）：
#   GITEE_TOKEN=你的令牌 ./release/publish.sh 0.4.7 "本次更新说明"
#
# 前提：APK 已编译并复制到 release/apk/iptv-player-<版本>.apk
# 令牌只从环境变量读，不写进任何文件；用后可从 Gitee 删除重建。

set -euo pipefail

VERSION="${1:?用法: GITEE_TOKEN=xxx ./release/publish.sh 0.4.7 \"更新说明\"}"
NOTES="${2:-魅影TV 更新}"
TAG="v${VERSION}"
OWNER="lzp-zl"
REPO="meiying-tv"
APK="release/apk/iptv-player-${VERSION}.apk"
TOKEN="${GITEE_TOKEN:?请先设置环境变量 GITEE_TOKEN}"

[ -f "$APK" ] || { echo "找不到 $APK，请先编译并复制"; exit 1; }
echo "发布 $TAG -> $APK ($(wc -c < "$APK") bytes)"

# 1. 若同名 Release 已存在则删掉重建（避免 Gitee 同名 tag 冲突）
existing=$(curl -s "https://gitee.com/api/v5/repos/${OWNER}/${REPO}/releases?access_token=${TOKEN}" \
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
python - "$APK_URL" "$NOTES" <<'PY'
import json, sys, re
url, notes = sys.argv[1:3]
src = open("android/app/build.gradle.kts", encoding="utf-8").read()
code = int(re.search(r"versionCode\s*=\s*(\d+)", src).group(1))
name = re.search(r'versionName\s*=\s*"([^"]+)"', src).group(1)
data = {"versionCode": code, "versionName": name, "apk": url, "notes": notes}
with open("release/update.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
print("update.json 已更新: vc%s / %s" % (code, name))
PY

# 5. 提交并双推（GitHub 默认分支是 main，master 也要同步，避免主页显示空仓库）
git add release/update.json
git commit -m "发布魅影TV ${TAG}" || echo "无改动可提交"
git push origin main:master
git push github main
git push github main:master

# 6. 端到端验证：线上 update.json -> 匿名下载 -> MD5 比对
ONLINE=$(curl -s "https://gitee.com/lzp-zl/meiying-tv/raw/master/release/update.json" \
  | python -c "import sys,json; print(json.load(sys.stdin)['apk'])")
echo "线上 apk: $ONLINE"
curl -sL --max-time 180 -o /tmp/meiying_verify.apk "$ONLINE"
LOCAL_MD5=$(md5sum "$APK" | cut -d' ' -f1)
REMOTE_MD5=$(md5sum /tmp/meiying_verify.apk | cut -d' ' -f1)
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] && echo "✅ 发布成功：匿名下载可用，MD5 一致" || { echo "❌ MD5 不一致"; exit 1; }
