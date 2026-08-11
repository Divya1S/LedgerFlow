#!/usr/bin/env bash
# Deploy LedgerFlow to a local kind cluster: the project's real, verified,
# zero-cost deployment target.
#
# Usage: ./deploy/kind/deploy-kind.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

CLUSTER=ledgerflow

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    echo "== creating kind cluster =="
    kind create cluster --name "$CLUSTER" --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" > /dev/null

echo "== building and loading app image =="
docker build -t ledgerflow:latest .
kind load docker-image ledgerflow:latest --name "$CLUSTER"

echo "== secrets (generated per cluster, never committed) =="
kubectl create secret generic ledgerflow-secrets \
    --from-literal=POSTGRES_PASSWORD="$(openssl rand -hex 16)" \
    --from-literal=JWT_SECRET="$(openssl rand -hex 32)" \
    --dry-run=client -o yaml | kubectl apply -f -

echo "== demo dependencies (postgres/kafka/redis, single node) =="
kubectl apply -f deploy/k8s/dependencies.yaml
kubectl rollout status deploy/postgres deploy/kafka deploy/redis --timeout=180s

echo "== application =="
helm upgrade --install ledgerflow deploy/k8s/ledgerflow
kubectl rollout status deploy/ledgerflow --timeout=300s

echo "== smoke test through the service =="
kubectl port-forward svc/ledgerflow 18080:8080 > /dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
sleep 3
HEALTH=$(curl -s http://localhost:18080/actuator/health | head -c 200)
echo "health: $HEALTH"
echo "$HEALTH" | grep -q '"status":"UP"' && echo "== kind deployment PASSED ==" || { echo "== kind deployment FAILED =="; exit 1; }
