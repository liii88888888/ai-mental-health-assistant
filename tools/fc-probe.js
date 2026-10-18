/**
 * Function Calling 探针：验证 AI 能不能真的读写业务数据
 * 用法：node tools/fc-probe.js <用户名> <密码>
 *
 * 验证四件事：
 *   ① 用户说"帮我记一下" → 模型调用 createDiary → 日记真的写进去了
 *   ② 再说一遍 → 不会插出第二条（唯一索引 upsert 的幂等）
 *   ③ 问"我这周心情怎么样" → 模型调用 queryEmotionTrend → 回答里带真实数据
 *   ④ 只读得到自己的数据（数据隔离）
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

/** 发一条消息，把 SSE 流里所有正文帧拼起来返回 */
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

/** 看今天那条日记的内容 */
async function todayDiary(token) {
    const r = await req('GET', '/api/emotion-diary/my?current=1&size=5', { token });
    const page = JSON.parse(r.text).data;
    const today = new Date().toISOString().slice(0, 10);
    const item = page.records.find(d => String(d.diaryDate).startsWith(today));
    return { total: page.total, today: item || null };
}

(async () => {
    const [, , username, password, otherUser, otherPass] = process.argv;
    if (!username || !password) {
        console.log('用法：node tools/fc-probe.js <用户名> <密码> [另一个用户名] [另一个密码]');
        return;
    }

    // ① 登录 + 建会话
    const login = await req('POST', '/api/user/login', { body: { username, password } });
    const token = JSON.parse(login.text).data.token;

    const start = await req('POST', '/api/psychological-chat/session/start', {
        token,
        body: { initialMessage: '你好', sessionTitle: '功能调用测试' }
    });
    const sessionId = JSON.parse(start.text).data.sessionId;
    console.log(`① 登录并创建会话：${sessionId}\n`);

    // ② 暴露一个意图：帮我记录
    console.log('② 发送：帮我把今天的感受记下来。我今天特别焦虑，因为明天要交论文，昨晚也没睡好。');
    let reply = await sendMessage(sessionId, '帮我把今天的感受记下来。我今天特别焦虑，因为明天要交论文，昨晚也没睡好。', token);
    console.log(`   AI 回复：${reply}\n`);

    let d = await todayDiary(token);
    console.log(`   → 今日日记：${d.today
        ? `评分=${d.today.moodScore} 情绪=${d.today.dominantEmotion} 内容="${String(d.today.diaryContent).slice(0, 40)}…"`
        : '❌ 没写进去'}`);
    console.log(`   → 日记总条数 = ${d.total}  ${d.today ? '✅ 工具写入成功' : '❌ 模型没调用工具'}\n`);

    // ③ 再说一遍 —— 验证幂等
    console.log('③ 再发一遍同样意图：真的帮我记一下，我今天特别焦虑，要交论文。');
    reply = await sendMessage(sessionId, '真的帮我记一下，我今天特别焦虑，要交论文。', token);
    console.log(`   AI 回复：${reply}\n`);

    const d2 = await todayDiary(token);
    console.log(`   → 日记总条数 = ${d2.total}  ${d2.total === d.total ? '✅ 没有重复插入（唯一索引 upsert 生效）' : '❌ 插出了重复记录'}\n`);

    // ④ 查询意图
    console.log('④ 发送：我这周心情怎么样？');
    reply = await sendMessage(sessionId, '我这周心情怎么样？', token);
    console.log(`   AI 回复：${reply}\n`);

    // ⑤ 数据隔离：换个账号问同样的问题，不该看到别人的日记
    if (otherUser && otherPass) {
        console.log(`⑤ 换账号 ${otherUser} 问同样的问题（验证数据隔离）`);
        const login2 = await req('POST', '/api/user/login', { body: { username: otherUser, password: otherPass } });
        const token2 = JSON.parse(login2.text).data.token;
        const start2 = await req('POST', '/api/psychological-chat/session/start', {
            token: token2,
            body: { initialMessage: '你好', sessionTitle: '隔离测试' }
        });
        const session2 = JSON.parse(start2.text).data.sessionId;

        const reply2 = await sendMessage(session2, '我这周心情怎么样？', token2);
        console.log(`   AI 回复：${reply2}\n`);

        // 用"交论文"这种只可能来自另一账号日记正文的词来判定，别用"焦虑"这种 AI 自己也会说的词
        const leaked = reply2.includes('论文') || reply2.includes('没睡好');
        console.log(`   → ${leaked
            ? '❌ 泄漏了 ' + username + ' 的日记内容'
            : '✅ 没读到别人的数据（工具内部的 userId 过滤生效）'}\n`);
    }
})();
