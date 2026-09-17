#!/usr/bin/env sh
set -eu
# Run from the repository root. Never overwrite an existing signing key.
if [ -e .local/jwt-private.pem ] && [ -e .local/jwt-public.pem ]; then
  echo "JWT keys already exist in .local; keeping them."
  exit 0
fi
if [ -e .local/jwt-private.pem ] || [ -e .local/jwt-public.pem ]; then
  echo "Only one JWT key exists; restore the matching pair before continuing." >&2
  exit 1
fi
umask 077
mkdir -p .local
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/jwt-private.pem 2>/dev/null
openssl pkey -in .local/jwt-private.pem -pubout -out .local/jwt-public.pem
printf '%s\n' "Generated local JWT key pair in ignored .local directory."
