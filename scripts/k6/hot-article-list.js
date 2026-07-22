import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8989').replace(/\/$/, '');

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:hot-article-list}': [`p(95)<${__ENV.P95_MS || 500}`],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/blog/article/hotArticleList`, {
    tags: { endpoint: 'hot-article-list' },
    headers: { 'X-Request-ID': `k6-hot-${__VU}-${__ITER}` },
  });
  check(response, {
    'hot list status is 200': (result) => result.status === 200,
    'hot list business code is 200': (result) => result.json('code') === 200,
  });
  sleep(Number(__ENV.SLEEP_SECONDS || 1));
}
