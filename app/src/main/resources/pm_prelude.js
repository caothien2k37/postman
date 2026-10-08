/*
 * Prelude của sandbox: dựng đối tượng `pm` (tập con API Postman) trên cầu nối `__host`.
 * Viết bằng ES5 để chạy ổn định trên Rhino. Mọi truy cập biến/mạng đều đi qua __host (Java).
 */
(function (global) {
  'use strict';

  var H = global.__host;
  var CTX = JSON.parse(H.context());
  var R = CTX.response || null;

  // ------------------------------------------------------------------ tiện ích

  function isObj(v) { return v !== null && typeof v === 'object'; }

  function typeOf(v) { return Object.prototype.toString.call(v).slice(8, -1).toLowerCase(); }

  function toStr(v) {
    if (v === undefined || v === null) return '';
    if (typeof v === 'string') return v;
    if (typeof v === 'object') {
      try { return JSON.stringify(v); } catch (e) { return String(v); }
    }
    return String(v);
  }

  function show(v) {
    if (typeof v === 'string') return v;
    if (v === undefined) return 'undefined';
    if (v === null) return 'null';
    if (typeof v === 'function') return '[Function]';
    if (v instanceof Error) return (v.name || 'Error') + ': ' + v.message;
    if (typeof v === 'object') {
      try { return JSON.stringify(v); } catch (e) { return String(v); }
    }
    return String(v);
  }

  function inspect(v) {
    var t = typeOf(v);
    if (t === 'string') return "'" + v + "'";
    if (t === 'undefined') return 'undefined';
    if (t === 'null') return 'null';
    if (t === 'function') return '[Function]';
    try {
      var s = JSON.stringify(v);
      return s === undefined ? String(v) : s;
    } catch (e) { return String(v); }
  }

  function stripBom(s) { return (s.charCodeAt(0) === 0xFEFF) ? s.slice(1) : s; }

  function deepEqual(a, b) {
    if (a === b) return true;
    var ta = typeOf(a), tb = typeOf(b);
    if (ta !== tb) return false;
    if (ta === 'number') return a !== a && b !== b;
    if (ta === 'date') return a.getTime() === b.getTime();
    if (ta === 'regexp') return String(a) === String(b);
    var i;
    if (ta === 'array') {
      if (a.length !== b.length) return false;
      for (i = 0; i < a.length; i++) { if (!deepEqual(a[i], b[i])) return false; }
      return true;
    }
    if (ta === 'object') {
      var ka = Object.keys(a), kb = Object.keys(b);
      if (ka.length !== kb.length) return false;
      for (i = 0; i < ka.length; i++) {
        var k = ka[i];
        if (!Object.prototype.hasOwnProperty.call(b, k) || !deepEqual(a[k], b[k])) return false;
      }
      return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ console

  function logger(level) {
    return function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) parts.push(show(arguments[i]));
      H.log(level, parts.join(' '));
    };
  }
  var consoleObj = { log: logger('log'), info: logger('info'), warn: logger('warn'), error: logger('error'), debug: logger('debug') };

  // ------------------------------------------------------------------ assertion kiểu Chai

  function AssertionError(message) {
    this.name = 'AssertionError';
    this.message = message;
  }
  AssertionError.prototype = Object.create(Error.prototype);
  AssertionError.prototype.constructor = AssertionError;
  AssertionError.prototype.toString = function () { return this.name + ': ' + this.message; };

  function Assertion(obj, flags, msg) {
    this._obj = obj;
    this._f = flags || {};
    this._msg = msg;
  }
  var AP = Assertion.prototype;

  function withFlags(f, extra) {
    var o = {}, k;
    for (k in f) { if (Object.prototype.hasOwnProperty.call(f, k)) o[k] = f[k]; }
    for (k in extra) { if (Object.prototype.hasOwnProperty.call(extra, k)) o[k] = extra[k]; }
    return o;
  }

  var CHAINS = ['to', 'be', 'been', 'is', 'that', 'which', 'and', 'has', 'have', 'with', 'at', 'of', 'same', 'but', 'does', 'still', 'also'];
  var FLAGS = ['not', 'deep', 'nested', 'own', 'any', 'all', 'ordered'];

  CHAINS.forEach(function (n) {
    Object.defineProperty(AP, n, { get: function () { return this; } });
  });
  FLAGS.forEach(function (n) {
    Object.defineProperty(AP, n, {
      get: function () {
        var e = {};
        e[n] = (n === 'not') ? !this._f.not : true;
        return new Assertion(this._obj, withFlags(this._f, e), this._msg);
      }
    });
  });

  AP._assert = function (ok, pos, neg) {
    if (this._f.not) ok = !ok;
    if (!ok) throw new AssertionError((this._msg ? this._msg + ': ' : '') + (this._f.not ? neg : pos));
    return this;
  };

  var PROPS = ['true', 'false', 'null', 'undefined', 'NaN', 'ok', 'exist', 'empty', 'finite'];

  /*
   * Trả về một hàm có thể gọi (vd .include(x), .length(3)) đồng thời mang đủ phương thức/chuỗi của Assertion
   * (vd .include.keys('a'), .length.above(2)).
   */
  function callable(self, fn, subject, extraFlags) {
    var a = new Assertion(subject, withFlags(self._f, extraFlags || {}), self._msg);
    function f() { return fn.apply(self, arguments); }
    var k;
    for (k in AP) {
      if (typeof AP[k] === 'function') {
        (function (key) { f[key] = function () { return a[key].apply(a, arguments); }; })(k);
      }
    }
    CHAINS.forEach(function (n) { f[n] = a; });
    FLAGS.concat(PROPS).forEach(function (n) {
      Object.defineProperty(f, n, { get: function () { return a[n]; } });
    });
    return f;
  }

  function eqTest(self, expected) { return self._f.deep ? deepEqual(self._obj, expected) : self._obj === expected; }

  AP.equal = AP.equals = AP.eq = function (v) {
    return this._assert(eqTest(this, v),
      'expected ' + inspect(this._obj) + ' to equal ' + inspect(v),
      'expected ' + inspect(this._obj) + ' to not equal ' + inspect(v));
  };
  AP.eql = AP.eqls = function (v) {
    return this._assert(deepEqual(this._obj, v),
      'expected ' + inspect(this._obj) + ' to deeply equal ' + inspect(v),
      'expected ' + inspect(this._obj) + ' to not deeply equal ' + inspect(v));
  };
  AP.above = AP.gt = AP.greaterThan = function (n) {
    return this._assert(this._obj > n, 'expected ' + inspect(this._obj) + ' to be above ' + n, 'expected ' + inspect(this._obj) + ' to be at most ' + n);
  };
  AP.least = AP.gte = function (n) {
    return this._assert(this._obj >= n, 'expected ' + inspect(this._obj) + ' to be at least ' + n, 'expected ' + inspect(this._obj) + ' to be below ' + n);
  };
  AP.below = AP.lt = AP.lessThan = function (n) {
    return this._assert(this._obj < n, 'expected ' + inspect(this._obj) + ' to be below ' + n, 'expected ' + inspect(this._obj) + ' to be at least ' + n);
  };
  AP.most = AP.lte = function (n) {
    return this._assert(this._obj <= n, 'expected ' + inspect(this._obj) + ' to be at most ' + n, 'expected ' + inspect(this._obj) + ' to be above ' + n);
  };
  AP.within = function (a, b) {
    return this._assert(this._obj >= a && this._obj <= b,
      'expected ' + inspect(this._obj) + ' to be within ' + a + '..' + b,
      'expected ' + inspect(this._obj) + ' to not be within ' + a + '..' + b);
  };
  AP.closeTo = AP.approximately = function (v, d) {
    return this._assert(Math.abs(this._obj - v) <= d,
      'expected ' + inspect(this._obj) + ' to be close to ' + v + ' +/- ' + d,
      'expected ' + inspect(this._obj) + ' not to be close to ' + v + ' +/- ' + d);
  };
  AP.a = AP.an = function (type) {
    var t = typeOf(this._obj);
    return this._assert(t === String(type).toLowerCase(),
      'expected ' + inspect(this._obj) + ' to be a ' + type,
      'expected ' + inspect(this._obj) + ' not to be a ' + type);
  };

  function includeFn(v) {
    var o = this._obj, ok = false, t = typeOf(o), i;
    if (t === 'string') {
      ok = o.indexOf(String(v)) !== -1;
    } else if (t === 'array') {
      for (i = 0; i < o.length; i++) {
        if (this._f.deep ? deepEqual(o[i], v) : o[i] === v) { ok = true; break; }
      }
    } else if (t === 'object' && isObj(v)) {
      ok = true;
      for (var k in v) {
        if (!(k in o) || !(this._f.deep ? deepEqual(o[k], v[k]) : o[k] === v[k])) { ok = false; break; }
      }
    }
    return this._assert(ok,
      'expected ' + inspect(o) + ' to include ' + inspect(v),
      'expected ' + inspect(o) + ' to not include ' + inspect(v));
  }
  ['include', 'includes', 'contain', 'contains'].forEach(function (n) {
    Object.defineProperty(AP, n, {
      get: function () { return callable(this, includeFn, this._obj, { contain: true }); }
    });
  });

  AP.property = function (name, value) {
    var o = this._obj, has, val, i;
    if (this._f.nested) {
      var cur = o;
      has = true;
      var parts = String(name).replace(/\[(\d+)\]/g, '.$1').split('.');
      for (i = 0; i < parts.length; i++) {
        if (cur !== null && cur !== undefined && Object(cur) === cur && parts[i] in cur) cur = cur[parts[i]];
        else { has = false; break; }
      }
      val = cur;
    } else {
      has = o !== null && o !== undefined &&
        (this._f.own ? Object.prototype.hasOwnProperty.call(o, name) : (Object(o) === o ? (name in o) : o[name] !== undefined));
      val = has ? o[name] : undefined;
    }
    var withValue = arguments.length > 1;
    var ok = has && (!withValue || (this._f.deep ? deepEqual(val, value) : val === value));
    this._assert(ok,
      'expected ' + inspect(o) + ' to have property ' + inspect(name) + (withValue ? ' of ' + inspect(value) + ', but got ' + inspect(val) : ''),
      'expected ' + inspect(o) + ' to not have property ' + inspect(name) + (withValue ? ' of ' + inspect(value) : ''));
    return new Assertion(val, {}, this._msg); // giống chai: chủ thể mới là giá trị của property
  };
  AP.ownProperty = AP.haveOwnProperty = function (name) {
    return this.own.property(name);
  };

  AP.keys = AP.key = function () {
    var ks = Array.prototype.slice.call(arguments);
    if (ks.length === 1 && isObj(ks[0])) ks = Array.isArray(ks[0]) ? ks[0] : Object.keys(ks[0]);
    var actual = Object.keys(this._obj);
    var all = ks.every(function (k) { return actual.indexOf(k) !== -1; });
    var any = ks.some(function (k) { return actual.indexOf(k) !== -1; });
    var ok;
    if (this._f.any) ok = any;
    else if (this._f.contain) ok = all;
    else ok = all && actual.length === ks.length;
    return this._assert(ok,
      'expected ' + inspect(this._obj) + ' to have keys ' + inspect(ks),
      'expected ' + inspect(this._obj) + ' to not have keys ' + inspect(ks));
  };

  AP.members = function (set) {
    var o = this._obj;
    var subset = set.every(function (m) { return o.some(function (x) { return deepEqual(x, m); }); });
    var ok = this._f.contain ? subset : (subset && o.length === set.length);
    return this._assert(ok,
      'expected ' + inspect(o) + ' to have members ' + inspect(set),
      'expected ' + inspect(o) + ' to not have members ' + inspect(set));
  };

  AP.lengthOf = function (n) {
    var l = (this._obj === null || this._obj === undefined) ? undefined : this._obj.length;
    return this._assert(l === n,
      'expected ' + inspect(this._obj) + ' to have a length of ' + n + ' but got ' + l,
      'expected ' + inspect(this._obj) + ' to not have a length of ' + n);
  };
  Object.defineProperty(AP, 'length', {
    get: function () {
      var subject = (this._obj === null || this._obj === undefined) ? undefined : this._obj.length;
      return callable(this, this.lengthOf, subject, {});
    }
  });

  AP.match = function (re) {
    return this._assert(re.test(this._obj),
      'expected ' + inspect(this._obj) + ' to match ' + re,
      'expected ' + inspect(this._obj) + ' not to match ' + re);
  };
  AP.string = function (s) {
    return this._assert(typeof this._obj === 'string' && this._obj.indexOf(s) !== -1,
      'expected ' + inspect(this._obj) + ' to contain ' + inspect(s),
      'expected ' + inspect(this._obj) + ' to not contain ' + inspect(s));
  };
  AP.oneOf = function (list) {
    var o = this._obj;
    return this._assert(list.some(function (x) { return x === o; }),
      'expected ' + inspect(o) + ' to be one of ' + inspect(list),
      'expected ' + inspect(o) + ' not to be one of ' + inspect(list));
  };
  AP.instanceof = AP.instanceOf = function (c) {
    return this._assert(this._obj instanceof c,
      'expected ' + inspect(this._obj) + ' to be an instance of ' + (c && c.name),
      'expected ' + inspect(this._obj) + ' not to be an instance of ' + (c && c.name));
  };
  AP.satisfy = AP.satisfies = function (fn) {
    return this._assert(!!fn(this._obj),
      'expected ' + inspect(this._obj) + ' to satisfy ' + String(fn),
      'expected ' + inspect(this._obj) + ' to not satisfy ' + String(fn));
  };
  AP['throw'] = AP.throws = AP.Throw = function (expected) {
    var thrown = null, did = false;
    try { this._obj(); } catch (e) { did = true; thrown = e; }
    var ok = did;
    if (did && expected !== undefined) {
      var msg = thrown && thrown.message !== undefined ? String(thrown.message) : String(thrown);
      ok = (expected instanceof RegExp) ? expected.test(msg) : (typeof expected === 'string' ? msg.indexOf(expected) !== -1 : true);
    }
    return this._assert(ok, 'expected function to throw', 'expected function to not throw');
  };

  function prop(name, test, desc) {
    Object.defineProperty(AP, name, {
      get: function () {
        return this._assert(test(this._obj),
          'expected ' + inspect(this._obj) + ' to ' + desc,
          'expected ' + inspect(this._obj) + ' to not ' + desc);
      }
    });
  }
  prop('true', function (o) { return o === true; }, 'be true');
  prop('false', function (o) { return o === false; }, 'be false');
  prop('null', function (o) { return o === null; }, 'be null');
  prop('undefined', function (o) { return o === undefined; }, 'be undefined');
  prop('NaN', function (o) { return typeof o === 'number' && o !== o; }, 'be NaN');
  prop('ok', function (o) { return !!o; }, 'be truthy');
  prop('exist', function (o) { return o !== null && o !== undefined; }, 'exist');
  prop('finite', function (o) { return typeof o === 'number' && isFinite(o); }, 'be finite');
  prop('empty', function (o) {
    var t = typeOf(o);
    if (t === 'string' || t === 'array') return o.length === 0;
    if (t === 'object') return Object.keys(o).length === 0;
    return false;
  }, 'be empty');

  function expect(v, msg) { return new Assertion(v, {}, msg); }
  expect.fail = function (m) { throw new AssertionError(m || 'expect.fail()'); };

  // ------------------------------------------------------------------ headers / cookies

  function HeaderList(pairs) { this._p = pairs || []; }
  HeaderList.prototype.get = function (name) {
    var n = String(name).toLowerCase();
    for (var i = 0; i < this._p.length; i++) {
      if (this._p[i][0].toLowerCase() === n) return this._p[i][1];
    }
    return undefined;
  };
  HeaderList.prototype.has = function (name) { return this.get(name) !== undefined; };
  HeaderList.prototype.all = function () {
    return this._p.map(function (h) { return { key: h[0], value: h[1] }; });
  };
  HeaderList.prototype.each = function (fn) { this.all().forEach(fn); };
  HeaderList.prototype.count = function () { return this._p.length; };
  HeaderList.prototype.toObject = function () {
    var o = {};
    this._p.forEach(function (h) { o[h[0]] = h[1]; });
    return o;
  };

  var cookieApi = {
    get: function (n) { var v = H.cookie(String(n)); return v === null ? undefined : v; },
    has: function (n) { return H.cookie(String(n)) !== null; }
  };

  // ------------------------------------------------------------------ phạm vi biến

  function scopeApi(scope) {
    return {
      get: function (k) { var v = H.getVar(scope, String(k)); return v === null ? undefined : v; },
      set: function (k, v) { H.setVar(scope, String(k), toStr(v)); },
      unset: function (k) { H.unsetVar(scope, String(k)); },
      has: function (k) { return H.hasVar(scope, String(k)); },
      clear: function () { H.clearVars(scope); },
      toObject: function () { return JSON.parse(H.allVars(scope)); },
      replaceIn: function (s) { return H.replaceIn(String(s)); }
    };
  }
  var envApi = scopeApi('environment');
  envApi.name = CTX.info.environmentName || '';
  var globalsApi = scopeApi('globals');
  var collApi = scopeApi('collectionVariables');
  var varsApi = scopeApi('variables');
  var dataApi = scopeApi('iterationData');

  // ------------------------------------------------------------------ pm.request

  function reqNow() { return JSON.parse(H.request()); }

  function makeUrl(raw) {
    var m = /^(?:([a-zA-Z][a-zA-Z0-9+.\-]*):\/\/)?([^\/?#]*)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/.exec(raw) || [];
    var hostStr = m[2] || '';
    var port = '';
    var ci = hostStr.lastIndexOf(':');
    if (ci >= 0 && hostStr.indexOf('}') < ci) { port = hostStr.slice(ci + 1); hostStr = hostStr.slice(0, ci); }
    var pathStr = m[3] || '';
    var qs = m[4] || '';
    var query = qs ? qs.split('&').map(function (p) {
      var i = p.indexOf('=');
      return i < 0 ? { key: p, value: '' } : { key: p.slice(0, i), value: p.slice(i + 1) };
    }) : [];
    query.get = function (k) {
      for (var i = 0; i < this.length; i++) { if (this[i].key === k) return this[i].value; }
      return undefined;
    };
    return {
      protocol: m[1] || '',
      host: hostStr ? hostStr.split('.') : [],
      port: port,
      path: pathStr.replace(/^\//, '').split('/').filter(function (s) { return s.length; }),
      query: query,
      hash: m[5] || '',
      getHost: function () { return hostStr; },
      getPath: function () { return pathStr || '/'; },
      getQueryString: function () { return qs; },
      getPathWithQuery: function () { return (pathStr || '/') + (qs ? '?' + qs : ''); },
      toString: function () { return raw; }
    };
  }

  function parseHeaderArg(h) {
    if (typeof h === 'string') {
      var i = h.indexOf(':');
      return { key: h.slice(0, i).trim(), value: h.slice(i + 1).trim() };
    }
    return { key: String(h.key), value: toStr(h.value) };
  }

  var requestHeaders = {
    add: function (h) { var p = parseHeaderArg(h); H.requestHeader('add', p.key, p.value); },
    upsert: function (h) { var p = parseHeaderArg(h); H.requestHeader('upsert', p.key, p.value); },
    remove: function (name) { H.requestHeader('remove', String(name), ''); },
    get: function (name) { return new HeaderList(reqNow().headers).get(name); },
    has: function (name) { return new HeaderList(reqNow().headers).has(name); },
    all: function () { return new HeaderList(reqNow().headers).all(); },
    each: function (fn) { this.all().forEach(fn); },
    toObject: function () { return new HeaderList(reqNow().headers).toObject(); }
  };

  var requestBody = {
    get mode() { return reqNow().body.mode; },
    get raw() { return reqNow().body.raw; },
    set raw(v) { H.requestSet('body', toStr(v)); },
    toString: function () { return reqNow().body.raw; }
  };

  var requestApi = {
    get method() { return reqNow().method; },
    set method(v) { H.requestSet('method', String(v)); },
    get url() { return makeUrl(reqNow().url); },
    set url(v) { H.requestSet('url', toStr(v)); },
    name: CTX.info.requestName || '',
    headers: requestHeaders,
    body: requestBody
  };

  // ------------------------------------------------------------------ pm.response

  var responseApi;
  if (R) {
    var respHeaders = new HeaderList(R.headers);
    responseApi = {
      code: R.code,
      status: R.status,
      responseTime: R.time,
      responseSize: R.size,
      headers: respHeaders,
      cookies: cookieApi,
      text: function () { return H.responseBody(); },
      json: function () { return JSON.parse(stripBom(H.responseBody())); }
    };

    var RA = function (neg) { this._neg = neg; };
    var RAP = RA.prototype;
    ['to', 'be', 'have', 'and', 'been', 'is', 'that', 'which', 'with'].forEach(function (n) {
      Object.defineProperty(RAP, n, { get: function () { return this; } });
    });
    Object.defineProperty(RAP, 'not', { get: function () { return new RA(!this._neg); } });
    RAP._check = function (ok, pos, neg) {
      if (this._neg) ok = !ok;
      if (!ok) throw new AssertionError(this._neg ? neg : pos);
      return this;
    };
    RAP.status = function (v) {
      var isCode = typeof v === 'number';
      var ok = isCode ? R.code === v : String(R.status).toLowerCase() === String(v).toLowerCase();
      return this._check(ok,
        'expected response to have status ' + (isCode ? 'code ' : 'reason ') + inspect(v) + ' but got ' + (isCode ? R.code : inspect(R.status)),
        'expected response to not have status ' + (isCode ? 'code ' : 'reason ') + inspect(v));
    };
    RAP.header = function (name, value) {
      var actual = respHeaders.get(name);
      var ok = actual !== undefined && (arguments.length < 2 || actual === value);
      return this._check(ok,
        'expected response to have header ' + inspect(name) + (arguments.length > 1 ? ' with value ' + inspect(value) : ''),
        'expected response to not have header ' + inspect(name));
    };
    RAP.body = function (expected) {
      var text = H.responseBody();
      var ok;
      if (expected === undefined) ok = text.length > 0;
      else if (expected instanceof RegExp) ok = expected.test(text);
      else ok = text.indexOf(String(expected)) !== -1;
      return this._check(ok, 'expected response body to match ' + inspect(expected), 'expected response body to not match ' + inspect(expected));
    };
    RAP.jsonBody = function (path, value) {
      var json;
      try { json = responseApi.json(); } catch (e) { return this._check(false, 'expected response to have a JSON body', 'expected response to not have a JSON body'); }
      if (path === undefined) return this._check(true, '', 'expected response to not have a JSON body');
      var cur = json, has = true;
      String(path).replace(/\[(\d+)\]/g, '.$1').split('.').forEach(function (p) {
        if (has && cur !== null && cur !== undefined && Object(cur) === cur && p in cur) cur = cur[p]; else has = false;
      });
      var ok = has && (arguments.length < 2 || deepEqual(cur, value));
      return this._check(ok, 'expected response JSON to have ' + inspect(path), 'expected response JSON to not have ' + inspect(path));
    };
    function rGetter(name, test, desc) {
      Object.defineProperty(RAP, name, {
        get: function () { return this._check(test(), 'expected response to be ' + desc + ' but got ' + R.code, 'expected response to not be ' + desc); }
      });
    }
    rGetter('ok', function () { return R.code === 200; }, '200 OK');
    rGetter('accepted', function () { return R.code === 202; }, '202 Accepted');
    rGetter('badRequest', function () { return R.code === 400; }, '400 Bad Request');
    rGetter('unauthorized', function () { return R.code === 401; }, '401 Unauthorized');
    rGetter('forbidden', function () { return R.code === 403; }, '403 Forbidden');
    rGetter('notFound', function () { return R.code === 404; }, '404 Not Found');
    rGetter('rateLimited', function () { return R.code === 429; }, '429 Too Many Requests');
    rGetter('info', function () { return R.code >= 100 && R.code < 200; }, 'informational (1xx)');
    rGetter('success', function () { return R.code >= 200 && R.code < 300; }, 'successful (2xx)');
    rGetter('redirection', function () { return R.code >= 300 && R.code < 400; }, 'a redirection (3xx)');
    rGetter('clientError', function () { return R.code >= 400 && R.code < 500; }, 'a client error (4xx)');
    rGetter('serverError', function () { return R.code >= 500 && R.code < 600; }, 'a server error (5xx)');
    rGetter('error', function () { return R.code >= 400 && R.code < 600; }, 'an error (4xx/5xx)');
    rGetter('withBody', function () { return H.responseBody().length > 0; }, 'a response with a body');
    rGetter('json', function () { try { responseApi.json(); return true; } catch (e) { return false; } }, 'JSON');
    responseApi.to = new RA(false);
  }

  // ------------------------------------------------------------------ pm.test / pm.sendRequest

  function test(name, fn) {
    var ok = true, msg = '';
    try {
      if (typeof fn === 'function') fn();
    } catch (e) {
      ok = false;
      msg = (e && e.message !== undefined) ? String(e.message) : String(e);
      if (e && e.name && e.name !== 'AssertionError') msg = e.name + ': ' + msg;
    }
    H.testResult(String(name), ok, msg);
  }
  test.skip = function (name) { H.testResult(String(name), true, 'skipped'); };

  function normHeaders(h) {
    var out = [];
    if (!h) return out;
    if (Array.isArray(h)) {
      h.forEach(function (x) { if (typeof x === 'string') { var p = parseHeaderArg(x); out.push([p.key, p.value]); } else if (x && x.key !== undefined && !x.disabled) out.push([String(x.key), toStr(x.value)]); });
    } else if (isObj(h)) {
      Object.keys(h).forEach(function (k) { out.push([k, toStr(h[k])]); });
    }
    return out;
  }
  function normFields(a) {
    var out = [];
    if (Array.isArray(a)) a.forEach(function (x) { if (x && x.key !== undefined && !x.disabled) out.push([String(x.key), toStr(x.value)]); });
    else if (isObj(a)) Object.keys(a).forEach(function (k) { out.push([k, toStr(a[k])]); });
    return out;
  }

  function sendRequest(req, cb) {
    var spec;
    if (typeof req === 'string') {
      spec = { url: req, method: 'GET', headers: [] };
    } else {
      var u = req.url;
      spec = {
        url: typeof u === 'string' ? u : (u && (u.raw || String(u))),
        method: String(req.method || 'GET'),
        headers: normHeaders(req.header || req.headers)
      };
      var b = req.body;
      if (typeof b === 'string') spec.body = { mode: 'raw', raw: b };
      else if (b && b.mode === 'raw') spec.body = { mode: 'raw', raw: toStr(b.raw) };
      else if (b && b.mode === 'urlencoded') spec.body = { mode: 'urlencoded', fields: normFields(b.urlencoded) };
      else if (b && b.mode === 'formdata') spec.body = { mode: 'formdata', fields: normFields(b.formdata) };
    }
    var out;
    try {
      out = JSON.parse(H.sendRequest(JSON.stringify(spec)));
    } catch (e) {
      if (cb) cb(e);
      return;
    }
    if (out.error) {
      if (cb) cb(new Error(out.error), undefined);
      return;
    }
    var res = {
      code: out.code,
      status: out.status,
      responseTime: out.time,
      headers: new HeaderList(out.headers),
      text: function () { return out.body; },
      json: function () { return JSON.parse(stripBom(out.body)); }
    };
    if (cb) cb(null, res);
  }

  // ------------------------------------------------------------------ pm

  function setNext(name) { H.setNextRequest(name === null || name === undefined ? null : String(name)); }

  var pm = {
    info: {
      eventName: CTX.info.eventName,
      iteration: CTX.info.iteration,
      iterationCount: CTX.info.iterationCount,
      requestName: CTX.info.requestName,
      requestId: CTX.info.requestId
    },
    environment: envApi,
    globals: globalsApi,
    collectionVariables: collApi,
    variables: varsApi,
    iterationData: dataApi,
    request: requestApi,
    response: responseApi,
    cookies: cookieApi,
    test: test,
    expect: expect,
    sendRequest: sendRequest,
    setNextRequest: setNext,
    execution: { setNextRequest: setNext }
  };

  // ------------------------------------------------------------------ API kiểu cũ (postman.*, tests[])

  global.pm = pm;
  global.console = consoleObj;
  global.tests = {};
  global.postman = {
    setEnvironmentVariable: function (k, v) { envApi.set(k, v); },
    getEnvironmentVariable: function (k) { var v = envApi.get(k); return v === undefined ? null : v; },
    clearEnvironmentVariable: function (k) { envApi.unset(k); },
    setGlobalVariable: function (k, v) { globalsApi.set(k, v); },
    getGlobalVariable: function (k) { var v = globalsApi.get(k); return v === undefined ? null : v; },
    clearGlobalVariable: function (k) { globalsApi.unset(k); },
    setNextRequest: setNext,
    getResponseHeader: function (n) {
      var v = responseApi ? responseApi.headers.get(n) : undefined;
      return v === undefined ? null : v;
    }
  };
  if (R) {
    global.responseBody = H.responseBody();
    global.responseCode = { code: R.code, name: R.status, detail: R.status };
    global.responseTime = R.time;
    global.responseHeaders = new HeaderList(R.headers).toObject();
  }
  global.data = dataApi.toObject();
  global.iteration = CTX.info.iteration;

  global.atob = function (s) { return H.atob(String(s)); };
  global.btoa = function (s) { return H.btoa(String(s)); };
  global.require = function (name) {
    throw new Error("require('" + name + "') chưa được hỗ trợ trong sandbox");
  };

  // setTimeout chạy đồng bộ sau khi script kết thúc (không chờ thật).
  var timers = [], timerSeq = 0;
  global.setTimeout = function (fn, ms) {
    var id = ++timerSeq;
    timers.push({ id: id, fn: fn, ms: Number(ms) || 0 });
    return id;
  };
  global.clearTimeout = function (id) {
    for (var i = 0; i < timers.length; i++) { if (timers[i].id === id) { timers.splice(i, 1); break; } }
  };
  global.__flush = function () {
    var guard = 0;
    while (timers.length && guard++ < 1000) {
      timers.sort(function (a, b) { return (a.ms - b.ms) || (a.id - b.id); });
      var t = timers.shift();
      if (typeof t.fn === 'function') t.fn();
    }
  };
})(this);
