/**
 * 会话管理 + 流式对话 探针
 * 用法：node tools/chat-probe.js <用户名> <密码>
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
            res.on('end', () => resolve({
                status: res.statusCode,
                headers: res.headers,
                text: Buffer.concat(chunks).toString('utf8')
            }));
        });
        r.on('error', reject);
        if (payload) r.write(payload);
        r.end();
    });
}

function show(label, r) {
    console.log(`\n===== ${label} =====`);
    console.log(`HTTP ${r.status}`);
    console.log(r.text.length > 400 ? r.text.slice(0, 400) + ' …' : r.text);
}

(async () => {
    const [, , username, password] = process.argv;
    if (!username || !password) {
        console.log('用法：node tools/chat-probe.js <用户名> <密码>');
        return;
    }

    // ① 登录
    const login = await req('POST', '/api/user/login', { body: { username, password } });
    const token = JSON.parse(login.text).data.token;
    console.log(`① 登录成功，token 长度 ${token.length}`);

    // ② 开始会话（同时存下开场白）
    const start = await req('POST', '/api/psychological-chat/session/start', {
        token,
        body: { initialMessage: '你好，我最近总是睡不着', sessionTitle: '失眠的困扰' }
    });
    show('② 开始会话', start);
    const sessionId = JSON.parse(start.text).data.sessionId;
    console.log(`\n>>> sessionId = ${sessionId}`);

    // ③ 会话列表
    const list = await req('GET', '/api/psychological-chat/sessions?current=1&size=5', { token });
    show('③ 会话列表', list);

    // ④ 流式对话
    console.log('\n===== ④ 流式对话 =====');
    const payload = JSON.stringify({ sessionId, userMessage: '我一躺下就胡思乱想，怎么办？' });
    await new Promise((resolve, reject) => {
        const r = http.request({
            host: HOST, port: PORT, method: 'POST', path: '/api/psychological-chat/stream',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
                token
            }
        }, res => {
            console.log(`HTTP ${res.statusCode}  Content-Type: ${res.headers['content-type']}`);
            let raw = '';
            res.on('data', d => {
                const t = d.toString('utf8');
                raw += t;
                const preview = t.length > 100 ? t.slice(0, 100) + '…' : t;
                console.log('  ' + JSON.stringify(preview));
            });
            res.on('end', () => {
                console.log('\n  ---- 完整字节流 ----');
                console.log(raw.split('\n').slice(0, 12).join('\n'));
                console.log('  ...');
                console.log('\n  含具名事件 event:done ? ' + (raw.includes('event:done') ? '✅ 是' : '❌ 否'));
                resolve();
            });
        });
        r.on('error', reject);
        r.write(payload);
        r.end();
    });

    // ⑤ 会话详情（消息是否落库）
    const detail = await req('GET', `/api/psychological-chat/sessions/${sessionId}/messages`, { token });
    console.log(`\n===== ⑤ 会话消息（应该 4 条：开场白 + 回复 + 提问 + 回复） =====`);
    const messages = JSON.parse(detail.text).data || [];
    console.log(`消息条数 = ${messages.length}`);
    messages.forEach(m => console.log(`  [${m.senderType === 1 ? '用户' : 'AI'}] ${String(m.content).slice(0, 50)}`));
})();
