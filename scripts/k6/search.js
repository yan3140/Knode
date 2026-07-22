import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8989').replace(/\/$/, '');
const keyword = encodeURIComponent(__ENV.KEYWORD || 'SpringSecurity');

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:search}': [`p(95)<${__ENV.P95_MS || 500}`],
  },
};

export default function () {
  const url = `${baseUrl}/api/blog/search?keyword=${keyword}&pageNum=1&pageSize=10&sort=relevance`;
  const response = http.get(url, {
    tags: { endpoint: 'search' },
    headers: { 'X-Request-ID': `k6-search-${__VU}-${__ITER}` },
  });
  check(response, {
    'search status is 200': (result) => result.status === 200,
    'search business code is 200': (result) => result.json('code') === 200,
    'search returns page data': (result) => result.json('data.total') !== undefined,
  });
  sleep(Number(__ENV.SLEEP_SECONDS || 1));
}
