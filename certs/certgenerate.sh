#!/bin/bash

set -o errexit

CERT_DIR="${1:-$(pwd)/opensearch}"
CN_NAME="${2:-opensearch.local}"

mkdir -p "$CERT_DIR"
cd "$CERT_DIR"

echo "Generating certificates in: $CERT_DIR"
echo "Using Common Name (CN): $CN_NAME"

# 1. Generate Root CA
openssl genrsa -out root-ca.key 2048
openssl req -x509 -new -nodes -key root-ca.key -sha256 -days 3650 -out root-ca.pem \
  -subj "/C=US/ST=CA/L=San Francisco/O=ExampleOrg/CN=OpenSearch Root CA"

# 2. Generate Server Private Key and CSR
openssl genrsa -out opensearch.key 2048
openssl req -new -key opensearch.key -out opensearch.csr \
  -subj "/C=US/ST=CA/L=San Francisco/O=ExampleOrg/CN=${CN_NAME}"

# 3. Create SAN extensions config
cat > extfile.cnf <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = ${CN_NAME}
DNS.2 = localhost
EOF

# 4. Sign Server Certificate with Root CA
openssl x509 -req -in opensearch.csr -CA root-ca.pem -CAkey root-ca.key -CAcreateserial \
  -out opensearch.pem -days 8250 -sha256 -extfile extfile.cnf

echo "Certificates generated:"
ls -1 "$CERT_DIR"

echo -e "\n✅ Done. You can now mount these into your Docker container for OpenSearch:"
echo " - opensearch.pem      (server cert)"
echo " - opensearch.key      (server key)"
echo " - root-ca.pem         (CA cert to trust the server)"
