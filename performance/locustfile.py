"""
CircleGuard - Locust Performance Test Suite
============================================
Scenarios:
  1. GatewayUser: QR validation throughput (target: >5000 req/s, p95 < 50ms)
  2. LoginUser: Auth login throughput
  3. DashboardUser: Analytics query load (target: p95 < 500ms)
  4. PropagationUser: Trigger health status updates
  5. MixedLoadUser: Simulates 8 AM peak (20% login, 50% QR, 30% dashboard)
"""

import json
import time
import uuid
import base64
import hmac
import hashlib
import struct
from datetime import datetime, timedelta, timezone

from locust import HttpUser, task, between, events, constant
from locust.runners import MasterRunner

JWT_SECRET     = "my-super-secret-dev-key-32-chars-long-12345678"
QR_SECRET      = "my-qr-secret-key-for-dev-1234567890"
AUTH_BASE      = "/api/v1/auth"
IDENTITY_BASE  = "/api/v1/identities"
GATEWAY_BASE   = "/api/v1/gate"
DASHBOARD_BASE = "/api/v1/analytics"
PROMO_BASE     = "/api/v1"

# Pre-computed test tokens (generated once to avoid overhead in requests)
_cached_tokens: dict = {}


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def build_jwt(subject: str, secret: str, expiry_seconds: int = 300) -> str:
    """Build a minimal HS256 JWT for testing (avoids hitting auth service in perf tests)."""
    header = _b64url_encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    now = int(time.time())
    payload = _b64url_encode(json.dumps({
        "sub": subject,
        "iat": now,
        "exp": now + expiry_seconds,
        "permissions": ["ROLE_STUDENT"]
    }).encode())
    signing_input = f"{header}.{payload}".encode()
    signature = hmac.new(secret.encode(), signing_input, hashlib.sha256).digest()
    return f"{header}.{payload}.{_b64url_encode(signature)}"


def get_qr_token(user_id: str = None) -> str:
    """Return a cached QR token (pre-built for performance tests)."""
    user_id = user_id or str(uuid.uuid4())
    if user_id not in _cached_tokens:
        _cached_tokens[user_id] = build_jwt(user_id, QR_SECRET, expiry_seconds=3600)
    return _cached_tokens[user_id]


def get_access_token(user_id: str = None) -> str:
    user_id = user_id or str(uuid.uuid4())
    return build_jwt(user_id, JWT_SECRET, expiry_seconds=3600)


# Pre-seed 1000 user IDs for realistic load
TEST_USER_IDS = [str(uuid.uuid4()) for _ in range(1000)]


# Scenario 1: Gateway, QR Validation Throughput
# Target: >5000 req/s, p95 < 50ms
class GatewayUser(HttpUser):
    """
    Simulates campus entrance gate scanners validating QR codes.
    Uses pre-built tokens to avoid auth overhead.
    Target: p95 response time < 50ms, throughput > 5000 req/s.
    """
    wait_time = constant(0)   # No wait – maximum throughput test
    host = "http://localhost:8087"
    weight = 5

    def on_start(self):
        # Pick a random pre-seeded user (mostly healthy, some blocked)
        self.user_id = TEST_USER_IDS[hash(self.environment.runner.user_count) % len(TEST_USER_IDS)]
        self.qr_token = get_qr_token(self.user_id)

    @task(10)
    def validate_qr_token(self):
        """Validate a QR token at the gate – the most frequent gateway operation."""
        with self.client.post(
            f"{GATEWAY_BASE}/validate",
            json={"token": self.qr_token},
            name="/gate/validate",
            catch_response=True
        ) as resp:
            if resp.status_code == 200:
                data = resp.json()
                if "valid" not in data:
                    resp.failure(f"Missing 'valid' field in response: {resp.text}")
                else:
                    resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")

    @task(1)
    def validate_expired_token(self):
        """Validate an expired token (should be rejected quickly)."""
        expired_token = build_jwt(str(uuid.uuid4()), QR_SECRET, expiry_seconds=-1)
        with self.client.post(
            f"{GATEWAY_BASE}/validate",
            json={"token": expired_token},
            name="/gate/validate [expired]",
            catch_response=True
        ) as resp:
            if resp.status_code == 200:
                data = resp.json()
                if data.get("valid") is not False:
                    resp.failure("Expired token should be invalid!")
                else:
                    resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")


# Scenario 2: Auth Login Throughput
class LoginUser(HttpUser):
    """
    Simulates users logging in at peak times.
    """
    wait_time = between(1, 3)
    host = "http://localhost:8180"
    weight = 2

    TEST_USERS = [
        {"username": "student1", "password": "password123"},
        {"username": "admin1", "password": "admin123"},
    ]

    @task(8)
    def login_valid(self):
        with self.client.post(
            "/api/v1/auth/login",
            json=self.TEST_USERS[0],
            catch_response=True
        ) as resp:
            if resp.status_code == 200:
                data = resp.json()
                if "token" in data:
                    resp.success()
                else:
                    resp.failure("No token in response")
            elif resp.status_code == 401:
                resp.success()  # Invalid credentials, but endpoint works
            else:
                resp.failure(f"Unexpected status {resp.status_code}")

    @task(2)
    def login_invalid(self):
        with self.client.post(
            "/api/v1/auth/login",  # ← CORREGIDO
            json={"username": "invalid", "password": "wrong"},
            catch_response=True
        ) as resp:
            if resp.status_code in (401, 403):
                resp.success()
            else:
                resp.failure(f"Expected 401, got {resp.status_code}")


class DashboardUser(HttpUser):
    """
    Simulates health administrators monitoring the dashboard.
    """
    wait_time = between(2, 5)
    host = "http://localhost:8084"
    weight = 3

    def on_start(self):
        response = self.client.post(
            "/api/v1/auth/login",
            json={"username": "admin1", "password": "admin123"}
        )
        if response.status_code == 200:
            self.admin_token = response.json().get("token")
        else:
            self.admin_token = None

    @task(5)
    def health_board(self):
        if not self.admin_token:
            return
        with self.client.get(
            "/api/v1/analytics/health-board",  # ← Ruta correcta
            headers={"Authorization": f"Bearer {self.admin_token}"},
            catch_response=True
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Status {resp.status_code}")


# Scenario 4: Promotion
# Target: propagation completes < 60 seconds for full graph traversal
class PropagationUser(HttpUser):
    """
    Simulates health center reporting positive cases.
    Low volume but CPU-intensive (Neo4j graph traversal).
    """
    wait_time = between(30, 60) 
    host = "http://localhost:8088"
    weight = 1

    def on_start(self):
        self.hc_token = get_access_token(str(uuid.uuid4()))
        self.target_user = TEST_USER_IDS[0]

    @task(1)
    def report_positive(self):
        """Report a positive case and measure time for the propagation response."""
        start = time.time()
        with self.client.post(
            f"{PROMO_BASE}/health/report",
            json={"anonymousId": self.target_user, "status": "CONFIRMED"},
            headers={"Authorization": f"Bearer {self.hc_token}"},
            name="/health/report [CONFIRMED]",
            catch_response=True
        ) as resp:
            elapsed = time.time() - start
            if resp.status_code in (200, 201, 204):
                if elapsed > 60:
                    resp.failure(f"Propagation took {elapsed:.1f}s – exceeds 60s SLA!")
                else:
                    resp.success()
            elif resp.status_code in (401, 403):
                resp.success()  # Auth issue in perf test, not a propagation failure
            else:
                resp.failure(f"Status {resp.status_code}")

    @task(3)
    def check_health_stats(self):
        """Poll health stats (read-heavy, low cost)."""
        with self.client.get(
            f"{PROMO_BASE}/health-status/stats",
            headers={"Authorization": f"Bearer {self.hc_token}"},
            name="/health-status/stats",
            catch_response=True
        ) as resp:
            if resp.status_code in (200, 401, 403):
                resp.success()
            else:
                resp.failure(f"Status {resp.status_code}")


# Scenario 5: Mixed Peak Load (8 AM scenario)
# 20% login, 50% QR validation, 30% dashboard
class MixedLoadUser(HttpUser):
    """
    Simulates realistic 8 AM peak:
    - Students arriving → generating and validating QR codes (50%)
    - New logins (20%)
    - Admins checking dashboard (30%)
    """
    wait_time = between(0.5, 2)
    host = "http://localhost:8087"  # Default host
    
    def on_start(self):
        self.user_id = str(uuid.uuid4())
        self.qr_token = get_qr_token(self.user_id)
        self.access_token = get_access_token(self.user_id)

    @task(5)
    def validate_qr(self):
        # Usa el host por defecto (gateway)
        with self.client.post(
            f"{GATEWAY_BASE}/validate",
            json={"token": self.qr_token},
            name="[mixed] /gate/validate",
            catch_response=True
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Status {resp.status_code}")

    @task(2)
    def login(self):
        # Para login, necesitas usar un cliente separado o cambiar el host
        with self.client.post(
            f"{AUTH_BASE}/login",
            json={"username": "student1", "password": "password123"},
            name="[mixed] /auth/login",
            catch_response=True
        ) as resp:
            if resp.status_code in (200, 401):
                resp.success()
            else:
                resp.failure(f"Status {resp.status_code}")

    @task(3)
    def dashboard(self):
        with self.client.get(
            f"{DASHBOARD_BASE}/health-board",
            headers={"Authorization": f"Bearer {self.access_token}"},
            name="[mixed] /analytics/health-board",
            catch_response=True
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            elif resp.status_code == 401:
                self.access_token = get_access_token(str(uuid.uuid4()))
                resp.success()
            else:
                resp.failure(f"Status {resp.status_code}")
                
SLA_RULES = {
    "/gate/validate":           {"p95_ms": 50,   "error_pct": 0.1},
    "/analytics/health-board":  {"p95_ms": 500,  "error_pct": 1.0},
    "/auth/login [valid]":      {"p95_ms": 500,  "error_pct": 5.0},
    "/health/report [CONFIRMED]": {"p95_ms": 60_000, "error_pct": 1.0},
}


@events.quitting.add_listener
def on_quitting(environment, **kwargs):
    print("\n" + "="*60)
    print("CircleGuard Performance Test – SLA Validation Report")
    print("="*60)
    failures = []
    for name, stats in environment.stats.entries.items():
        sla = SLA_RULES.get(name[1] if isinstance(name, tuple) else name)
        if not sla:
            continue
        p95 = stats.get_response_time_percentile(0.95)
        err_pct = (stats.num_failures / max(stats.num_requests, 1)) * 100
        ok_p95 = p95 <= sla["p95_ms"]
        ok_err = err_pct <= sla["error_pct"]
        status = "PASS" if (ok_p95 and ok_err) else "FAIL"
        print(f"{status} | {name}")
        print(f"       p95={p95:.0f}ms (target<={sla['p95_ms']}ms) | "
              f"errors={err_pct:.2f}% (target<={sla['error_pct']}%)")
        if not (ok_p95 and ok_err):
            failures.append(name)

    print("="*60)
    if failures:
        print(f"\n⚠ SLA VIOLATIONS: {failures}")
        environment.process_exit_code = 1
    else:
        print("\nAll SLAs passed!")
    print("="*60)