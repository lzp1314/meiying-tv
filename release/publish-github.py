#!/usr/bin/env python3
"""魅影TV GitHub Release 发布脚本（与 Gitee 的 publish.sh 配对）

用法（仓库根目录）：
    GITHUB_TOKEN=xxx HTTPS_PROXY=http://127.0.0.1:64828 python release/publish-github.py 0.5.3 "更新说明"

说明：
- 版本号与 versionCode 一律从 android/app/build.gradle.kts 读取，不手写，避免清单与实际包不符。
- APK 取 release/apk/iptv-player-<版本>.apk，必须已用 release/sign-apk.sh 签好。
- 同名 tag 已存在时先删 Release（再删 tag），保证重复执行是幂等的。
- tag 指向完整 40 位 commit SHA（GitHub API 不接受短 SHA，会误报 tag 无效）。
- 只从环境变量读令牌，不落盘、不写进任何文件。
"""
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

OWNER = "lzp1314"
REPO = "meiying-tv"
API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"


def api(path, method="GET", payload=None, token=None, raw=False, url=None):
    target = url or (API + path)
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(target, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "meiying-tv-release")
    if data:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read()
            if raw:
                return body
            return json.loads(body.decode("utf-8")) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:600]
        raise SystemExit("GitHub API %s %s 失败 HTTP %s: %s" % (method, target, e.code, detail))


def gradle_versions():
    src = open("android/app/build.gradle.kts", encoding="utf-8").read()
    code = int(re.search(r"versionCode\s*=\s*(\d+)", src).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', src).group(1)
    return name, code


def full_sha(rev="HEAD"):
    return subprocess.check_output(
        ["git", "rev-parse", rev + "^{commit}"], text=True).strip()


def main():
    if len(sys.argv) < 2:
        raise SystemExit('用法: GITHUB_TOKEN=xxx python release/publish-github.py 0.5.3 "更新说明"')
    version = sys.argv[1].lstrip("v")
    notes = sys.argv[2] if len(sys.argv) > 2 else "魅影TV 更新"
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        raise SystemExit("请先设置环境变量 GITHUB_TOKEN")

    name, code = gradle_versions()
    if name != version:
        raise SystemExit("build.gradle.kts 里是 %s，命令行给的是 %s，先对齐版本号" % (name, version))

    apk = "release/apk/iptv-player-%s.apk" % version
    if not os.path.isfile(apk):
        raise SystemExit("找不到 %s" % apk)
    size = os.path.getsize(apk)

    tag = "v" + version
    # 默认指向当前 HEAD；要跟 Gitee 的发布提交对齐时用 TARGET_SHA 指定
    sha = full_sha(os.environ.get("TARGET_SHA", "HEAD"))
    print("发布 GitHub %s <- %s (%d bytes) @ %s" % (tag, apk, size, sha[:7]))

    # 幂等：同名 Release 先删，再删同名 tag ref
    existing = api("/repos/%s/%s/releases?per_page=100" % (OWNER, REPO), token=token)
    for rel in existing:
        if rel.get("tag_name") == tag:
            print("删除旧 Release %s (#%s)" % (tag, rel["id"]))
            api("/repos/%s/%s/releases/%s" % (OWNER, REPO, rel["id"]), method="DELETE", token=token)
    try:
        api("/repos/%s/%s/git/refs/tags/%s" % (OWNER, REPO, tag), method="DELETE", token=token)
        print("旧 tag %s 已删除" % tag)
    except SystemExit:
        pass  # 没有这个 tag，正常

    rel = api("/repos/%s/%s/releases" % (OWNER, REPO), method="POST", token=token, payload={
        "tag_name": tag,
        "target_commitish": sha,
        "name": "魅影TV " + tag,
        "body": notes,
        "prerelease": False,
    })
    print("Release 已建: #%s" % rel["id"])

    # 上传 APK
    data = open(apk, "rb").read()
    req = urllib.request.Request(
        "%s/repos/%s/%s/releases/%s/assets?name=%s" % (UPLOAD, OWNER, REPO, rel["id"], os.path.basename(apk)),
        data=data, method="POST")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "meiying-tv-release")
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    with urllib.request.urlopen(req, timeout=1800) as r:
        asset = json.loads(r.read().decode("utf-8"))
    print("✅ APK 已上传: %s (%d bytes)" % (asset["browser_download_url"], asset["size"]))
    print("RELEASE_URL=" + rel["html_url"])


if __name__ == "__main__":
    main()
