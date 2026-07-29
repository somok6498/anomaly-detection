import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const errorRate = new Rate('errors');
const evalDuration = new Trend('eval_duration', true);

const CLIENT_IDS = [
  'CLIENT-001', 'CLIENT-002', 'CLIENT-003', 'CLIENT-004', 'CLIENT-005',
  'CLIENT-006', 'CLIENT-007', 'CLIENT-008', 'CLIENT-009', 'CLIENT-010',
  'LOAD-001', 'LOAD-002', 'LOAD-003', 'LOAD-004', 'LOAD-005',
];

const TXN_TYPES = ['NEFT', 'RTGS', 'IMPS', 'UPI', 'IFT'];
const IFSC_PREFIXES = ['HDFC', 'ICIC', 'SBIN', 'UTIB', 'KKBK'];

export const options = {
  scenarios: {
    evaluate_load: {
      executor: 'ramping-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 500,
      stages: [
        { target: 10,  duration: '30s' },   // warm-up
        { target: 50,  duration: '30s' },   // ramp to 50 TPS
        { target: 100, duration: '30s' },   // ramp to 100 TPS
        { target: 200, duration: '30s' },   // ramp to 200 TPS
        { target: 200, duration: '120s' },  // sustain 200 TPS for 2 min
        { target: 50,  duration: '30s' },   // ramp down
        { target: 0,   duration: '30s' },   // cool-down
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    errors: ['rate<0.01'],
  },
};

function randomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function generateTxn() {
  const clientId = randomItem(CLIENT_IDS);
  const txnType = randomItem(TXN_TYPES);
  const amount = Math.round((100 + Math.random() * 500000) * 100) / 100;
  const beneIfsc = randomItem(IFSC_PREFIXES) + '000' + String(Math.floor(Math.random() * 10000)).padStart(4, '0');
  const beneAcct = String(1000000000 + Math.floor(Math.random() * 900000000));

  return {
    txnId: `LOAD-${clientId}-${Date.now()}-${Math.floor(Math.random() * 100000)}`,
    clientId: clientId,
    txnType: txnType,
    amount: amount,
    timestamp: Date.now(),
    beneficiaryAccount: beneAcct,
    beneficiaryIfsc: beneIfsc,
  };
}

export default function () {
  const txn = generateTxn();
  const params = { headers: { 'Content-Type': 'application/json' } };

  const res = http.post(`${BASE_URL}/api/v1/transactions/evaluate`, JSON.stringify(txn), params);

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'has riskScore': (r) => {
      try { return JSON.parse(r.body).riskScore !== undefined; } catch (e) { return false; }
    },
    'has action': (r) => {
      try { return ['PASS', 'ALERT', 'BLOCK'].includes(JSON.parse(r.body).action); } catch (e) { return false; }
    },
  });

  errorRate.add(!ok);
  evalDuration.add(res.timings.duration);
}

export function handleSummary(data) {
  const p50 = data.metrics.http_req_duration.values['p(50)'];
  const p95 = data.metrics.http_req_duration.values['p(95)'];
  const p99 = data.metrics.http_req_duration.values['p(99)'];
  const errRate = data.metrics.errors ? data.metrics.errors.values.rate : 0;
  const totalReqs = data.metrics.http_reqs.values.count;

  const summary = `
=== Load Test Summary ===
Total requests:  ${totalReqs}
Error rate:      ${(errRate * 100).toFixed(2)}%
Latency p50:     ${p50.toFixed(1)}ms
Latency p95:     ${p95.toFixed(1)}ms
Latency p99:     ${p99.toFixed(1)}ms
=========================
`;

  return {
    stdout: summary,
  };
}
