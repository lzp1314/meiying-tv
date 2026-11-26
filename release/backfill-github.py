#!/usr/bin/env python3
"""补齐 GitHub Release（0.4.9 起的版本 Gitee 已发、GitHub 缺失）

用法（仓库根目录）：
    GITHUB_TOKEN=xxx python release/backfill-github.py            # 全部补齐
    GITHUB_TOKEN=xxx python release/backfill-github.py v0.5.0     # 只补某个

token 只需 fine-grained 的 Contents: Read and write（限定 meiying-tv 仓库即可）。
已存在的 Release 会跳过，不会重复建；APK 附件走 uploads.github.com。
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

OWNER = "lzp1314"
REPO = "meiying-tv"
APK_DIR = "release/apk"

# 每个版本：标签 -> (打标签的提交, apk 文件名, 说明)
# 提交与 Gitee 上同名标签保持一致，避免两端 tag 指向不同代码
RELEASES = [
    (
        "v0.4.9",
        "aadfdda",
        "记住上次台改用稳定标识（订阅顺序变了也不串台）、缓存保鲜延长到 24 小时（启动更快）；"
        "修复频道→信号源切页焦点打架；精简设置页光标并加醒目蓝色描边。",
    ),
    (
        "v0.4.10",
        "31766da",
        "修复老 Android 设备（Android 7-9 运营商盒子）点检查更新报 Unacceptable certificate 的问题："
        "内置 TrustAsia 根证书兜底，不再依赖系统信任库，在线更新恢复可用。",
    ),
    (
        "v0.4.11",
        "45ba648",
        "修 0.4.10 在老 Android 盒子仍报 Unacceptable certificate 的问题："
        "改用自签名根 Certum Trusted Network CA（标准 PKIX 路径）+ 合并系统信任库，"
        "并在设置页新增“证书兜底”诊断行。",
    ),
    (
        "v0.5.0",
        "68d4bb6",
        "内置 VLC 播放引擎兜底：硬解失败、长时间无画面、或流格式 ExoPlayer 不支持时，自动改用 VLC 重播同一路流；"
        "音频轨解码失败不再整条流报错，改为禁音轨保画面。新增遥控器数字键换台：1.6 秒内连按累积，按 OK 跳转对应序号频道。"
        "设置页新增「播放引擎」可选 自动 / ExoPlayer / VLC。安装包增至 58MB（内置 VLC 库）。",
    ),
]

API = "https://api.github.com"


def full_sha(rev):
    """短 SHA / 标签名 → GitHub 接受的完整 40 位提交 SHA

    GitHub 的 target_commitish 只认完整 SHA 或分支名，传短 SHA 会 422，
    还会连带报一句误导性的 “tag_name is not a valid tag”。
    """
    out = subprocess.run(
        ["git", "rev-parse", rev + "^{commit}"], capture_output=True, text=True
    )
    if out.returncode != 0:
        raise RuntimeError("无法解析提交 %s: %s" % (rev, out.stderr.strip()))
    return out.stdout.strip()



def call(method, path, token, payload=None):
    """调 GitHub REST，返回解析后的 JSON；4xx/5xx 直接抛错"""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "User-Agent": "meiying-tv-release",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise RuntimeError("%s %s -> HTTP %s: %s" % (method, path, e.code, detail)) from None


def existing_releases(token):
    """已有 Release：{tag: {"id":.., "assets":[名字..]}}"""
    out = {}
    page = 1
    while True:
        rows = call("GET", "/repos/%s/%s/releases?per_page=100&page=%d" % (OWNER, REPO, page), token)
        if not rows:
            break
        for r in rows:
            out[r["tag_name"]] = {"id": r["id"], "assets": [a["name"] for a in r["assets"]]}
        if len(rows) < 100:
            break
        page += 1
    return out


def upload_asset(upload_url, token, apk_path):
    """上传附件（curl 处理大文件 multipart 最稳，避免自己拼 boundary）"""
    url = upload_url.split("{")[0]
    name = os.path.basename(apk_path)
    # GitHub 要求 name 走查询参数、二进制放 body，所以直接拼查询串 + --data-binary
    cmd = [
        "curl", "-s", "-X", "POST", "%s?name=%s" % (url, name),
        "-H", "Authorization: Bearer " + token,
        "-H", "Accept: application/vnd.github+json",
        "-H", "Content-Type: application/vnd.android.package-archive",
        "-H", "User-Agent: meiying-tv-release",
        "--data-binary", "@" + apk_path,
    ]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    try:
        d = json.loads(out)
    except json.JSONDecodeError:
        raise RuntimeError("附件上传返回非 JSON: " + out[:300]) from None
    if "browser_download_url" not in d:
        raise RuntimeError("附件上传失败: " + json.dumps(d, ensure_ascii=False)[:300])
    return d["browser_download_url"]


def main():
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        sys.exit("请先设置环境变量 GITHUB_TOKEN")

    only = sys.argv[1] if len(sys.argv) > 1 else None
    have = existing_releases(token)
    print("GitHub 已有 Release：%s" % (", ".join(sorted(have)) or "无"))

    todo = [r for r in RELEASES if only is None or r[0] == only]
    if not todo:
        sys.exit("没有匹配 %s 的版本" % only)

    ok, skipped, failed = [], [], []
    for tag, sha, notes in todo:
        apk = os.path.join(APK_DIR, "iptv-player-%s.apk" % tag.lstrip("v"))
        cur = have.get(tag)

        # 1) Release 已存在：只在缺附件时补传
        if cur:
            if "iptv-player-%s.apk" % tag.lstrip("v") in cur["assets"]:
                print("· %s 已有 Release 且附件齐全，跳过" % tag)
                skipped.append(tag)
                continue
            rel = call("GET", "/repos/%s/%s/releases/%d" % (OWNER, REPO, cur["id"]), token)
            print("· %s 有 Release 但缺附件，补传" % tag)
        else:
            if not os.path.exists(apk):
                print("✗ %s 本地找不到 %s" % (tag, apk))
                failed.append(tag)
                continue
            size = os.path.getsize(apk)
            sha_full = full_sha(sha)
            print("· %s 建 Release（commit %s，附件 %.1fMB）" % (tag, sha_full[:7], size / 1048576))
            rel = call(
                "POST",
                "/repos/%s/%s/releases" % (OWNER, REPO),
                token,
                {
                    "tag_name": tag,
                    "target_commitish": sha_full,
                    "name": "魅影TV " + tag,
                    "body": notes,
                    "draft": False,
                    "prerelease": False,
                },
            )
            print("  Release id=%s, tag 已建在 %s" % (rel["id"], sha_full[:7]))

        # 2) 传附件
        if not os.path.exists(apk):
            print("✗ %s 本地找不到 %s" % (tag, apk))
            failed.append(tag)
            continue
        try:
            url = upload_asset(rel["upload_url"], token, apk)
            print("  ✅ 附件直链: %s" % url)
            ok.append(tag)
        except RuntimeError as e:
            print("  ✗ %s" % e)
            failed.append(tag)

    print("\n汇总：成功 %d %s / 跳过 %d %s / 失败 %d %s"
          % (len(ok), ok, len(skipped), skipped, len(failed), failed))
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
