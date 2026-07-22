import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8989').replace(/\/$/, '');
const articleId = __ENV.ARTICLE_ID || '1';

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:comment-list}': [`p(95)<${__ENV.P95_MS || 500}`],
  },
};

export default function () {
  const url = `${baseUrl}/api/blog/comment/commentList?articleId=${articleId}&pageNum=1&pageSize=10`;
  const response = http.get(url, {
    tags: { endpoint: 'comment-list' },
    headers: { 'X-Request-ID': `k6-comment-${__VU}-${__ITER}` },
  });
  check(response, {
    'comment list status is 200': (result) => result.status === 200,
    'comment list business code is 200': (result) => result.json('code') === 200,
  });
  sleep(Number(__ENV.SLEEP_SECONDS || 1));
}
