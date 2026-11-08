#!/usr/bin/env bash
set -euo pipefail

# Authentication is deliberately checked before any interactive prompt.
if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required." >&2
  exit 1
fi
gh auth status

repo=""
prefix="INDEXPAINT"
keystore_arg=""
while (($#)); do
  case "$1" in
    --repo) repo="${2:?Missing value for --repo}"; shift 2 ;;
    --prefix) prefix="${2:?Missing value for --prefix}"; shift 2 ;;
    --help)
      echo "Usage: $0 [--repo OWNER/REPO] [--prefix SECRET_PREFIX] [keystore.jks]"
      exit 0
      ;;
    --*) echo "Unknown option: $1" >&2; exit 2 ;;
    *)
      if [[ -n "$keystore_arg" ]]; then echo "Only one keystore path may be supplied." >&2; exit 2; fi
      keystore_arg="$1"
      shift
      ;;
  esac
done

if [[ -z "$repo" ]]; then
  repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
fi
if [[ ! "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "Repository must be OWNER/REPO; got '$repo'." >&2
  exit 2
fi
if [[ ! "$prefix" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
  echo "Secret prefix must use uppercase letters, digits, and underscores." >&2
  exit 2
fi
for tool in java javac openssl shasum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Required command not found: $tool" >&2; exit 1; }
done

if [[ -z "$keystore_arg" ]]; then
  while :; do
    read -r -p "Path to the original local JKS: " keystore_arg
    [[ -f "$keystore_arg" ]] && break
    echo "Sorry, couldn't find that file. Try again."
  done
elif [[ ! -f "$keystore_arg" ]]; then
  echo "Keystore not found: $keystore_arg" >&2
  exit 1
fi
keystore="$(cd "$(dirname "$keystore_arg")" && pwd)/$(basename "$keystore_arg")"
alias="${INDEXPAINT_KEY_ALIAS:-vh}"
read -r -p "Key alias [$alias]: " alias_input
alias="${alias_input:-$alias}"

read -r -s -p "Current local keystore password: " old_store_password; echo
read -r -s -p "Current local private-key password: " old_key_password; echo

tmp="$(mktemp -d "${TMPDIR:-/tmp}/github-signing-secrets.XXXXXX")"
chmod 700 "$tmp"
cleanup() {
  unset old_store_password old_key_password new_store_password new_key_password keystore_b64
  rm -rf -- "$tmp"
}
trap cleanup EXIT HUP INT TERM

source_hash="$(shasum -a 256 "$keystore" | awk '{print $1}')"
new_store_password="$(openssl rand -hex 32)"
new_key_password="$(openssl rand -hex 32)"
copy="$tmp/ci-upload.jks"
cp "$keystore" "$copy"
chmod 600 "$copy"
mkdir "$tmp/classes"
javac -d "$tmp/classes" "$(dirname "$0")/ReencryptKeystore.java"
fingerprint="$(OLD_STORE_PASSWORD="$old_store_password" \
  OLD_KEY_PASSWORD="$old_key_password" \
  NEW_STORE_PASSWORD="$new_store_password" \
  NEW_KEY_PASSWORD="$new_key_password" \
  INDEXPAINT_KEYSTORE_TYPE="${INDEXPAINT_KEYSTORE_TYPE:-JKS}" \
  java -cp "$tmp/classes" ReencryptKeystore "$keystore" "$copy" "$alias")"

if [[ "$(shasum -a 256 "$keystore" | awk '{print $1}')" != "$source_hash" ]]; then
  echo "The source keystore changed unexpectedly; refusing to update GitHub." >&2
  exit 1
fi
keystore_b64="$(base64 < "$copy" | tr -d '\n')"
if (( ${#keystore_b64} > 48000 )); then
  echo "Base64 keystore exceeds the conservative GitHub Actions secret size limit." >&2
  exit 1
fi

secret_keystore="${prefix}_UPLOAD_KEYSTORE_BASE64"
secret_store_password="${prefix}_KEYSTORE_PASSWORD"
secret_key_password="${prefix}_KEY_PASSWORD"
variable_alias="${prefix}_KEY_ALIAS"
cat <<EOF

Plan for $repo:
  Read the local source keystore: $keystore
  Re-encrypt a temporary copy for alias: $alias
  Signing certificate SHA-1: $fingerprint
  Update repository secret: $secret_keystore
  Update repository secret: $secret_store_password
  Update repository secret: $secret_key_password
  Set repository variable:   $variable_alias

The source keystore will remain unchanged. Two new random passwords were generated;
they will not be displayed or saved locally. Continue with these GitHub updates? [y/N]
EOF
read -r confirmation
if [[ "$confirmation" != "y" && "$confirmation" != "Y" ]]; then
  echo "Cancelled. No GitHub settings were changed."
  exit 0
fi

echo "Writing repository secret $secret_keystore..."
printf '%s' "$keystore_b64" | gh secret set "$secret_keystore" --repo "$repo"
echo "Writing repository secret $secret_store_password..."
printf '%s' "$new_store_password" | gh secret set "$secret_store_password" --repo "$repo"
echo "Writing repository secret $secret_key_password..."
printf '%s' "$new_key_password" | gh secret set "$secret_key_password" --repo "$repo"
gh variable set "$variable_alias" --repo "$repo" --body "$alias"

if [[ "$(shasum -a 256 "$keystore" | awk '{print $1}')" != "$source_hash" ]]; then
  echo "ERROR: source keystore hash changed during the update." >&2
  exit 1
fi
echo "GitHub signing secrets updated. Original local keystore is unchanged."
