/**
 * 知识库 Cache-Aside 探针
 * 用法：node tools/article-probe.js <用户名> <密码> <分类ID>
 *
 * 它会走一遍缓存的生命周期：
 *   建文章 → 读两次（第一次回源、第二次命中缓存）→ 阅读+1（缓存失效）→ 再读 → 删除 → 再读
 *
 * 「命中缓存 / 回源数据库」的判定要看后端控制台的 [Cache-Aside] 日志。
 */

const http = require('http');

const HOST = '127.0.0.1';
const PORT = Number(process.env.PROBE_PORT || 8080);

function req(method, path, { token, body } = {}) {
    return new Promise((resolve, reject) => {
        const headers = {};
        if (token) headers['token'] = token;
        let payload = null;
        if (body !== undefined) {
            payload = JSON.stringify(body);
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        const r = http.request({ host: HOST, port: PORT, method, path, headers }, res => {
            const chunks = [];
            res.on('data', c => chunks.push(c));
            res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
        });
        r.on('error', reject);
        if (payload) r.write(payload);
        r.end();
    });
}

(async () => {
    const [, , username, password, categoryIdArg] = process.argv;
    if (!username || !password) {
        console.log('用法：node tools/article-probe.js <用户名> <密码> [分类ID，默认 1]');
        return;
    }
    const categoryId = Number(categoryIdArg || 1);

    // ① 登录
    const login = await req('POST', '/api/user/login', { body: { username, password } });
    const token = JSON.parse(login.text).data.token;
    console.log(`① 登录成功`);

    // ② 新建文章
    const create = await req('POST', '/api/knowledge/article', {
        token,
        body: {
            categoryId,
            title: '焦虑的时候，试试 4-7-8 呼吸法',
            summary: '一个简单到随时能用的放松技巧',
            content: '吸气 4 秒，屏住 7 秒，呼气 8 秒。重复四轮。'
                   + '它的原理是让呼气比吸气长，从而激活副交感神经，让身体先松下来。',
            tags: '焦虑,呼吸,放松'
        }
    });
    const article = JSON.parse(create.text).data;
    console.log(`② 新建文章 id = ${article.id}，readCount = ${article.readCount}`);
    const id = article.id;

    // ③ 读两次 —— 第一次回源数据库、第二次应该命中缓存（看后端日志）
    let r = await req('GET', `/api/knowledge/article/${id}`, { token });
    let a = JSON.parse(r.text).data;
    console.log(`③ 第一次读：标题="${a.title}" readCount=${a.readCount}`);

    r = await req('GET', `/api/knowledge/article/${id}`, { token });
    a = JSON.parse(r.text).data;
    console.log(`   第二次读：标题="${a.title}" readCount=${a.readCount}`);

    // ④ 阅读 +1 —— 数据变了，缓存应该被清掉
    await req('PUT', `/api/knowledge/article/${id}/read`, { token });
    r = await req('GET', `/api/knowledge/article/${id}`, { token });
    a = JSON.parse(r.text).data;
    console.log(`④ 阅读 +1 后再读：readCount = ${a.readCount}  ${a.readCount === 1 ? '✅ 读到的是数据库的新值' : '❌ 读到旧值，缓存没失效'}`);

    // ⑤ 删除 —— 先删库、再删缓存
    const del = await req('DELETE', `/api/knowledge/article/${id}`, { token });
    console.log(`⑤ 删除文章：HTTP ${del.status}  ${del.text}`);

    // ⑥ 删完再读 —— 应该查不到（而不是从缓存里读到"幽灵文章"）
    r = await req('GET', `/api/knowledge/article/${id}`, { token });
    const msg = JSON.parse(r.text).message;
    console.log(`⑥ 删除后再读：${msg}  ${msg.includes('不存在') ? '✅ 没有幽灵缓存' : '❌ 缓存没删干净'}`);
})();
