/**
 * 探针：验证 AI 对"今天/昨天/这周"这类相对时间的理解。
 * 用法：node tools/date-probe.js <用户名> <密码>
 *
 * 问三件事，看模型怎么答、有没有调工具、调的日期范围对不对。
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

function sendMessage(sessionId, userMessage, token) {
    return new Promise((resolve, reject) => {
        const payload = JSON.stringify({ sessionId, userMessage });
        const r = http.request({
            host: HOST, port: PORT, method: 'POST', path: '/api/psychological-chat/stream',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
                token
            }
        }, res => {
            let raw = '';
            res.on('data', d => { raw += d.toString('utf8'); });
            res.on('end', () => resolve(collectText(raw)));
        });
        r.on('error', reject);
        r.write(payload);
        r.end();
    });
}

function collectText(raw) {
    let text = '';
    for (const frame of raw.split('\n\n')) {
        const lines = frame.split('\n');
        if (lines.some(l => l.startsWith('event:') && l.includes('done'))) continue;
        const dataLine = lines.find(l => l.startsWith('data:'));
        if (!dataLine) continue;
        try {
            const obj = JSON.parse(dataLine.slice(5));
            if (obj.data && obj.data.content) text += obj.data.content;
        }
        catch (e) { /* 忽略不完整帧 */ }
    }
    return text;
}

(async () => {
    const [, , username, password, reuseSession] = process.argv;
    if (!username || !password) {
        console.log('用法：node tools/date-probe.js <用户名> <密码> [复用已有会话ID，如 session_87]');
        console.log('  不带第三个参数：新建一个干净会话（历史里没有旧消息）');
        console.log('  带第三个参数：在指定会话里提问（历史里可能有旧日期）');
        return;
    }

    const login = await req('POST', '/api/user/login', { body: { username, password } });
    const token = JSON.parse(login.text).data.token;

    const now = new Date();
    console.log('本机时间：' + now.toString());
    console.log('本机时区偏移：UTC' + (now.getTimezoneOffset() <= 0 ? '+' : '-')
        + Math.abs(now.getTimezoneOffset() / 60) + '\n');

    let sessionId;
    // 传 "-" 表示"明确要新会话"（PowerShell 会把空字符串参数丢掉，所以需要一个非空标记）
    if (reuseSession && reuseSession !== '-') {
        sessionId = reuseSession;
        console.log('复用已有会话 = ' + sessionId + '（历史里可能有昨天说过的日期）\n');
    } else {
        const start = await req('POST', '/api/psychological-chat/session/start', {
            token,
            body: { initialMessage: '你好', sessionTitle: '日期探测' }
        });
        sessionId = JSON.parse(start.text).data.sessionId;
        console.log('新建干净会话 = ' + sessionId + '\n');
    }

    // 第 4 个参数之后如果还有内容，就用它们当问题；否则用默认三问
    const extra = process.argv.slice(5);
    const questions = extra.length > 0 ? extra : [
        '今天是几号？星期几？',
        '我昨天心情怎么样？',
        '我这周心情怎么样？'
    ];

    for (const q of questions) {
        console.log('========== 问：' + q + ' ==========');
        const reply = await sendMessage(sessionId, q, token);
        console.log('答：' + reply + '\n');
    }
})();
