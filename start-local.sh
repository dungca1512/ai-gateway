#!/bin/bash

# AI Gateway Local Development Script
# For Mac M4 development

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

print_status() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

check_prerequisites() {
    echo "Checking prerequisites..."
    
    # Check Docker
    if command -v docker &> /dev/null; then
        print_status "Docker is installed"
    else
        print_warning "Docker not found - Redis will need manual setup"
    fi
    
    # Check Java
    if command -v java &> /dev/null; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1)
        print_status "Java: $JAVA_VERSION"
    else
        print_error "Java not found - required for Gateway"
        echo "Install with: brew install openjdk@21"
        exit 1
    fi
    
    # Check Python
    if command -v python3 &> /dev/null; then
        PYTHON_VERSION=$(python3 --version)
        print_status "Python: $PYTHON_VERSION"
    else
        print_error "Python3 not found - required for Worker"
        exit 1
    fi
    
    echo ""
}

start_redis() {
    echo "Starting Redis..."
    
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^redis$'; then
        print_status "Redis already running"
    else
        if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q '^redis$'; then
            docker start redis
        else
            docker run -d --name redis -p 6379:6379 redis:7-alpine
        fi
        print_status "Redis started on port 6379"
    fi
    echo ""
}

setup_worker() {
    echo "Setting up Python Worker..."
    
    cd "$PROJECT_DIR/worker"
    
    # Create venv if not exists
    if [ ! -d "venv" ]; then
        python3 -m venv venv
        print_status "Created virtual environment"
    fi
    
    # Activate and install dependencies
    source venv/bin/activate
    pip install -q -r requirements.txt
    print_status "Dependencies installed"
    
    echo ""
}

start_worker() {
    echo "Starting Python Worker..."
    
    cd "$PROJECT_DIR/worker"
    source venv/bin/activate
    
    # Kill existing worker if running
    pkill -f "uvicorn app:app" 2>/dev/null || true
    
    # Start in background
    nohup python -m uvicorn app:app --host 0.0.0.0 --port 8000 > worker.log 2>&1 &
    WORKER_PID=$!
    echo $WORKER_PID > worker.pid
    
    # Wait for startup
    sleep 3
    
    if curl -s http://localhost:8000/health > /dev/null; then
        print_status "Worker started on port 8000 (PID: $WORKER_PID)"
    else
        print_error "Worker failed to start. Check worker.log"
        cat worker.log
        exit 1
    fi
    echo ""
}

build_gateway() {
    echo "Building Java Gateway..."
    
    cd "$PROJECT_DIR/gateway"
    
    if [ -f "mvnw" ]; then
        chmod +x mvnw
        ./mvnw clean package -DskipTests -q
    else
        mvn clean package -DskipTests -q
    fi
    
    print_status "Gateway built successfully"
    echo ""
}

start_gateway() {
    echo "Starting Java Gateway..."
    
    cd "$PROJECT_DIR/gateway"
    
    # Kill existing gateway if running
    pkill -f "ai-gateway-1.0.0.jar" 2>/dev/null || true
    
    # Start in background
    nohup java -jar target/ai-gateway-1.0.0.jar > gateway.log 2>&1 &
    GATEWAY_PID=$!
    echo $GATEWAY_PID > gateway.pid
    
    # Wait for startup
    echo "Waiting for Gateway to start..."
    for i in {1..30}; do
        if curl -s http://localhost:8080/health > /dev/null; then
            print_status "Gateway started on port 8080 (PID: $GATEWAY_PID)"
            break
        fi
        sleep 1
        echo -n "."
    done
    echo ""
    
    if ! curl -s http://localhost:8080/health > /dev/null; then
        print_error "Gateway failed to start. Check gateway.log"
        tail -50 gateway.log
        exit 1
    fi
    echo ""
}

stop_all() {
    echo "Stopping all services..."
    
    # Stop Gateway
    if [ -f "$PROJECT_DIR/gateway/gateway.pid" ]; then
        kill $(cat "$PROJECT_DIR/gateway/gateway.pid") 2>/dev/null || true
        rm "$PROJECT_DIR/gateway/gateway.pid"
        print_status "Gateway stopped"
    fi
    
    # Stop Worker
    if [ -f "$PROJECT_DIR/worker/worker.pid" ]; then
        kill $(cat "$PROJECT_DIR/worker/worker.pid") 2>/dev/null || true
        rm "$PROJECT_DIR/worker/worker.pid"
        print_status "Worker stopped"
    fi
    
    pkill -f "uvicorn app:app" 2>/dev/null || true
    pkill -f "ai-gateway-1.0.0.jar" 2>/dev/null || true
    
    echo ""
}

show_status() {
    echo "Service Status:"
    echo "==============="
    
    # Check Redis
    if docker ps 2>/dev/null | grep -q redis; then
        print_status "Redis: Running on port 6379"
    else
        print_error "Redis: Not running"
    fi
    
    # Check Worker
    if curl -s http://localhost:8000/health > /dev/null 2>&1; then
        print_status "Worker: Running on port 8000"
    else
        print_error "Worker: Not running"
    fi
    
    # Check Gateway
    if curl -s http://localhost:8080/health > /dev/null 2>&1; then
        print_status "Gateway: Running on port 8080"
    else
        print_error "Gateway: Not running"
    fi
    
    echo ""
}

run_tests() {
    echo "Running API tests..."
    echo ""
    
    if [ -f "$PROJECT_DIR/test-api.sh" ]; then
        chmod +x "$PROJECT_DIR/test-api.sh"
        "$PROJECT_DIR/test-api.sh"
    else
        print_error "test-api.sh not found"
    fi
}

show_logs() {
    echo "Recent logs:"
    echo "============"
    echo ""
    echo "--- Worker logs ---"
    tail -20 "$PROJECT_DIR/worker/worker.log" 2>/dev/null || echo "No worker logs"
    echo ""
    echo "--- Gateway logs ---"
    tail -20 "$PROJECT_DIR/gateway/gateway.log" 2>/dev/null || echo "No gateway logs"
}

usage() {
    echo "AI Gateway Local Development Script"
    echo ""
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  start     Start all services (default)"
    echo "  stop      Stop all services"
    echo "  restart   Restart all services"
    echo "  status    Show service status"
    echo "  test      Run API tests"
    echo "  logs      Show recent logs"
    echo "  build     Build Gateway only"
    echo "  worker    Start Worker only"
    echo "  gateway   Start Gateway only"
    echo ""
}

# Main
case "${1:-start}" in
    start)
        echo "========================================"
        echo "  AI Gateway - Local Development"
        echo "========================================"
        echo ""
        check_prerequisites
        start_redis
        setup_worker
        start_worker
        build_gateway
        start_gateway
        echo "========================================"
        echo "  All services started!"
        echo "========================================"
        echo ""
        echo "Gateway:  http://localhost:8080"
        echo "Worker:   http://localhost:8000"
        echo "Redis:    localhost:6379"
        echo ""
        echo "Try: curl http://localhost:8080/health"
        echo "Or:  ./test-api.sh"
        echo ""
        ;;
    stop)
        stop_all
        ;;
    restart)
        stop_all
        sleep 2
        exec "$0" start
        ;;
    status)
        show_status
        ;;
    test)
        run_tests
        ;;
    logs)
        show_logs
        ;;
    build)
        build_gateway
        ;;
    worker)
        setup_worker
        start_worker
        ;;
    gateway)
        build_gateway
        start_gateway
        ;;
    help|--help|-h)
        usage
        ;;
    *)
        echo "Unknown command: $1"
        usage
        exit 1
        ;;
esac
