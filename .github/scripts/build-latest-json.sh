#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 生成 Pages 站点数据（双通道，对照 unicanta 的 pages.yml）：
#   1) 稳定版：取最新的 v* 正式 Release，镜像 APK 到 docs/apk/，
#      写出 docs/latest.json（应用内更新检查读取这份）；
#   2) 开发版：取滚动的 dev 预发布 Release，解析 release notes 里的
#      version / development-build / Commit 字段，镜像 APK 并写出
#      docs/development.json（下载页开发版渠道与应用内 dev 渠道共用）。
#
# 用法：GITHUB_TOKEN=xxx bash .github/scripts/build-latest-json.sh docs
# ---------------------------------------------------------------------------
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-303562915/tv}"
API="https://api.github.com/repos/${REPO}"
SITE_DIR="${1:-docs}"
MIRROR_DIR="$SITE_DIR/apk"
MIRROR_COUNT=3

auth=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
  auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

mkdir -p "$MIRROR_DIR"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
releases="$tmpdir/releases.json"

if ! curl -sSL --fail "${auth[@]}" -H "Accept: application/vnd.github+json" \
      "${API}/releases?per_page=30" -o "$releases"; then
  echo "warning: 读取 releases 失败" >&2
  echo '[]' > "$releases"
fi

# ---------------------------------------------------------------------------
# 稳定版通道：v* 正式 Release（不含 dev / latest 等滚动预发布）
# ---------------------------------------------------------------------------
jq -r '
  [ .[]
    | select(.draft == false)
    | select(.tag_name | test("^v[0-9]"))
    | . as $r
    | ([.assets[] | select(.name | test("\\.apk$"; "i"))][0]) as $a
    | select($a != null)
    | [$r.tag_name, $a.name, ($a.size | tostring), $a.browser_download_url,
       ($r.published_at // ""), (($r.body // "") | gsub("[\t\n\r]"; " "))]
  ] | .[]
  | @tsv' "$releases" > "$tmpdir/stable.tsv"

entries="$tmpdir/entries.jsonl"
: > "$entries"
sha=""
notes=""
file_rel=""
file_name=""
file_size="0"
published=""
version_name=""
version_code="0"
count=0

while IFS=$'\t' read -r tag name size url published_at body; do
  [ -n "${tag:-}" ] || continue
  count=$((count + 1))

  label="${tag#v}"

  rel_uri=""
  if [ "$count" -le "$MIRROR_COUNT" ]; then
    safe=$(printf '%s-%s' "$tag" "$name" | tr -c 'A-Za-z0-9._-' '-')
    if curl -sSL --fail "${auth[@]}" -o "$MIRROR_DIR/$safe" "$url"; then
      rel_uri="apk/$safe"
      if [ "$count" -eq 1 ]; then
        sha=$(sha256sum "$MIRROR_DIR/$safe" | cut -d' ' -f1)
      fi
    else
      echo "warning: 下载 $name 失败，回退到 Release 直链" >&2
      rm -f "$MIRROR_DIR/$safe"
    fi
  fi
  [ -n "$rel_uri" ] || rel_uri="$url"

  if [ "$count" -eq 1 ]; then
    file_rel="$rel_uri"
    file_name="$name"
    file_size="$size"
    published="$published_at"
    notes="$body"
    # 版本三元组以该 tag 上的 Gradle 声明为准，而不是当前 HEAD
    gradle_b64=$(curl -sSL --fail "${auth[@]}" \
      "${API}/contents/tv_project/app/build.gradle.kts?ref=${tag}" \
      | jq -r '.content' | base64 -d 2>/dev/null || true)
    version_name=$(printf '%s' "$gradle_b64" | grep -m1 -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' | sed -E 's/.*"([^"]+)".*/\1/' || true)
    version_code=$(printf '%s' "$gradle_b64" | grep -m1 -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' | grep -oE '[0-9]+' || true)
    [ -n "$version_name" ] || version_name="$label"
    [ -n "$version_code" ] || version_code=0
  fi

  jq -cn --arg v "$label" --arg f "$rel_uri" --arg n "$name" \
        --argjson s "${size:-0}" --arg p "${published_at:-}" \
        '{version_name: $v, file: $f, file_name: $n, size: $s, published_at: $p}' >> "$entries"
done < "$tmpdir/stable.tsv"

history=$(jq -s '.[0:12]' "$entries" 2>/dev/null || echo '[]')
if [ -z "$history" ] || [ "$history" = "null" ]; then
  history='[]'
fi

jq -n \
  --arg version_name "$version_name" \
  --argjson version_code "$version_code" \
  --arg file "$file_rel" \
  --arg file_name "$file_name" \
  --argjson size "${file_size:-0}" \
  --arg published_at "$published" \
  --arg sha256 "$sha" \
  --arg notes "$notes" \
  --argjson history "$history" \
  '{
     version_name: $version_name,
     version_code: $version_code,
     file: $file,
     file_name: $file_name,
     size: $size,
     published_at: $published_at,
     sha256: $sha256,
     notes: $notes,
     history: $history,
     generated_at: (now | todate)
   }' > "$SITE_DIR/latest.json"

# ---------------------------------------------------------------------------
# 开发版通道：滚动 dev 预发布（release notes 承载 version / development-build / Commit）
# ---------------------------------------------------------------------------
python3 - "$REPO" "$SITE_DIR" "$MIRROR_DIR" "$tmpdir/releases.json" <<'PY'
import json, os, pathlib, subprocess, sys

repo, site_dir, mirror_dir, releases_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
token = os.environ.get("GITHUB_TOKEN", "")

empty = {
    "version_name": None, "version_code": 0, "build_number": None, "tag": None,
    "published_at": None, "file": None, "file_name": None, "size": 0,
    "sha256": "", "checksum_file": None, "notes": "", "history": [],
}

def write(data):
    out = pathlib.Path(site_dir) / "development.json"
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(data, ensure_ascii=False)[:400])

# GitHub API 的 /releases 返回扁平数组；兼容个别分页写法时兜底抹平一层
data = json.loads(pathlib.Path(releases_path).read_text(encoding="utf-8"))
if isinstance(data, list):
    releases = [r for page in data for r in (page if isinstance(page, list) else [page]) if isinstance(r, dict)]
else:
    releases = []
matches = [r for r in releases if r.get("tag_name") == "dev" and not r.get("draft")]
if not matches:
    write(empty)
    sys.exit(0)
rel = matches[0]
body = rel.get("body") or ""

def field(name, pattern):
    for line in body.splitlines():
        line = line.strip()
        if line.startswith(name + ":"):
            value = line[len(name) + 1:].strip()
            import re
            if re.fullmatch(pattern, value):
                return value
    return None

version = field("version", r"[vV]?[0-9]+(\.[0-9]+)+([+-][0-9A-Za-z.-]+)?")
build = field("development-build", r"[1-9][0-9]*")
commit = field("Commit", r"[0-9a-fA-F]{40}")
if not (version and build):
    write(empty)
    sys.exit(0)
version = version.lstrip("vV")
build = str(int(build))

name_prefix = f"TVPlayer-dev-{build}"
apks = [a for a in rel.get("assets", []) if a["name"].startswith(name_prefix) and a["name"].endswith(".apk")]
sums = [a for a in rel.get("assets", []) if a["name"] == f"SHA256SUMS-{build}.txt"]
if not apks:
    write(empty)
    sys.exit(0)
apk = apks[0]

# 版本码：该提交上的 Gradle 声明
version_code = 0
digest = ""
if commit:
    try:
        cmd = ["gh", "api", f"repos/{repo}/contents/tv_project/app/build.gradle.kts?ref={commit}", "--jq", ".content"]
        env = dict(os.environ)
        import base64
        content = base64.b64decode(subprocess.check_output(cmd, text=True, env=env)).decode("utf-8", "ignore")
        import re
        m = re.search(r"versionCode\s*=\s*(\d+)", content)
        if m:
            version_code = int(m.group(1))
    except Exception as e:
        print(f"warning: 读取 dev version_code 失败: {e}", file=sys.stderr)

# 镜像 APK 到 docs/apk/，算出 sha256（与应用内下载校验同一份值）
file_rel = apk["browser_download_url"]
safe = f"dev-{apk['name']}"
target = pathlib.Path(mirror_dir) / safe
try:
    import urllib.request
    req = urllib.request.Request(apk["browser_download_url"])
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=120) as resp, open(target, "wb") as fh:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)
    import hashlib
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    file_rel = f"apk/{safe}"
except Exception as e:
    print(f"warning: 镜像 dev APK 失败: {e}", file=sys.stderr)
    if target.exists():
        target.unlink()

checksum_file = sums[0]["browser_download_url"] if sums else None
entry = {
    "version_name": version,
    "version_code": version_code,
    "build_number": int(build),
    "tag": "dev",
    "published_at": rel.get("published_at"),
    "file": file_rel,
    "file_name": apk["name"],
    "size": apk.get("size", 0),
    "sha256": digest,
    "checksum_file": checksum_file,
    "notes": body.strip(),
}
write(dict(entry, history=[entry]))
PY

echo "written: $SITE_DIR/latest.json + $SITE_DIR/development.json"
