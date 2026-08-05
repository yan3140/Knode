import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8989').replace(/\/$/, '');
const target = (__ENV.TARGET || 'mixed').toLowerCase();
const iterations = Number(__ENV.ITERATIONS || 10000);
const vus = Number(__ENV.VUS || 300);
const p95 = Number(__ENV.P95_MS || 1500);
const articleIds = (__ENV.ARTICLE_IDS || __ENV.ARTICLE_ID || '1')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const commentArticleId = __ENV.COMMENT_ARTICLE_ID || articleIds[0] || '1';
const keyword = encodeURIComponent(__ENV.KEYWORD || 'load');
const simulateIps = (__ENV.SIMULATE_IPS || 'true').toLowerCase() !== 'false';
const businessFailures = new Counter('business_failures');

export const options = {
  scenarios: {
    high_concurrency: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(95)<${p95}`],
  },
};

function getArticleId() {
  return articleIds[(__VU + __ITER) % articleIds.length];
}

function requestForTarget(name) {
  if (name === 'article') {
    return {
      endpoint: 'article-detail',
      url: `${baseUrl}/api/blog/article/${getArticleId()}`,
    };
  }
  if (name === 'view') {
    return {
      endpoint: 'view-count',
      url: `${baseUrl}/api/blog/article/updateViewCount/${getArticleId()}`,
      method: 'PUT',
    };
  }
  if (name === 'comment') {
    return {
      endpoint: 'comment-list',
      url: `${baseUrl}/api/blog/comment/commentList?articleId=${commentArticleId}&pageNum=1&pageSize=10`,
    };
  }
  if (name === 'search') {
    return {
      endpoint: 'search',
      url: `${baseUrl}/api/blog/search?keyword=${keyword}&pageNum=1&pageSize=10&sort=relevance`,
    };
  }
  if (name === 'hot') {
    return {
      endpoint: 'hot-article-list',
      url: `${baseUrl}/api/blog/article/hotArticleList`,
    };
  }
  const names = ['article', 'view', 'comment', 'search', 'hot'];
  return requestForTarget(names[__ITER % names.length]);
}

export default function () {
  const request = requestForTarget(target);
  const simulatedIp = `10.${__VU % 250}.${Math.floor(__ITER / 250) % 250}.${(__ITER % 250) + 1}`;
  const params = {
    tags: { endpoint: request.endpoint },
    headers: {
      'X-Request-ID': `k6-${request.endpoint}-${__VU}-${__ITER}`,
      'User-Agent': `YanBlogLoadTest/${__VU}-${__ITER}`,
    },
  };
  if (simulateIps) {
    params.headers['X-Forwarded-For'] = simulatedIp;
  }
  const response = request.method === 'PUT'
    ? http.put(request.url, null, params)
    : http.get(request.url, params);

  let businessCode = null;
  if (response.status === 200 && response.body) {
    try {
      businessCode = response.json('code');
    } catch (error) {
      businessCode = 'json_error';
    }
  }
  if (businessCode !== 200) {
    businessFailures.add(1, {
      endpoint: request.endpoint,
      status: String(response.status),
      code: String(businessCode),
    });
  }

  check(response, {
    [`${request.endpoint} HTTP 200`]: (result) => result.status === 200,
    [`${request.endpoint} business code 200`]: () => businessCode === 200,
  });
}
