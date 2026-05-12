# Despliega todo el entorno DEV en Kubernetes

set -e

echo "Deploying CircleGuard DEV environment..."

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

echo "Creating namespace..."
kubectl apply -f k8s/dev/namespace.yaml

echo "Applying secrets..."
kubectl apply -f k8s/dev/secrets.yaml

echo "Deploying infrastructure..."
kubectl apply -f k8s/dev/infrastructure/

echo "Waiting for infrastructure to be ready..."
kubectl wait --for=condition=ready pod -l app=postgres -n dev --timeout=300s
kubectl wait --for=condition=ready pod -l app=neo4j -n dev --timeout=300s
kubectl wait --for=condition=ready pod -l app=kafka -n dev --timeout=300s

echo "Deploying microservices..."
kubectl apply -f k8s/dev/services/

echo "Waiting for microservices to be ready..."
kubectl wait --for=condition=ready pod -l app=identity-service -n dev --timeout=300s
kubectl wait --for=condition=ready pod -l app=auth-service -n dev --timeout=300s
kubectl wait --for=condition=ready pod -l app=promotion-service -n dev --timeout=300s

echo -e "${GREEN} DEV environment deployed successfully!${NC}"
echo ""
echo "Useful commands:"
echo "  kubectl get pods -n dev"
echo "  kubectl get services -n dev"
echo "  ./scripts/k8s/status.sh"