/**
 * 探针：同一天写两次日记，到底是"追加"还是"覆盖"？
 * 用法：node tools/fc-overwrite-probe.js <用户名> <密码>
 *
 * 剧本：早上记一笔倒霉事 → 下午记一笔开心事 → 回头看今天那条日记还剩什么
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

async function todayDiary(token) {
    const r = await req('GET', '/api/emotion-diary/my?current=1&size=5', { token });
    const page = JSON.parse(r.text).data;
    const today = new Date().toISOString().slice(0, 10);
    return { total: page.total, today: page.records.find(d => String(d.diaryDate).startsWith(today)) || null };
}

/** 实验前把今天那条删掉，保证每轮起点一致（可重复实验） */
async function resetToday(token) {
    const d = await todayDiary(token);
    if (d.today) {
        await req('DELETE', `/api/emotion-diary/my/${d.today.id}`, { token });
        console.log(`（已清掉今天的旧日记 id=${d.today.id}，保证起点一致）\n`);
    }
}

function show(tag, d) {
    if (!d.today) {
        console.log(`   ${tag}：今天没有日记`);
        return '';
    }
    const t = d.today;
    console.log(`   ${tag}：id=${t.id} 评分=${t.moodScore} 情绪=${t.dominantEmotion}`);
    console.log(`         睡眠=${t.sleepQuality ?? 'null'} 压力=${t.stressLevel ?? 'null'} 触发因素=${t.emotionTriggers ?? 'null'}`);
    console.log('         正文：');
    String(t.diaryContent).split('\n').forEach(line => console.log('           ' + line));
    return String(t.diaryContent ?? '');
}

(async () => {
    const [, , username, password, mode] = process.argv;
    if (!username || !password) {
        console.log('用法：node tools/fc-overwrite-probe.js <用户名> <密码> [split]');
        console.log('  不加 split：早上、下午在同一个会话里（历史里有早上那轮）');
        console.log('  加   split：下午另起一个新会话（模拟"中午关掉页面，下午再打开"）');
        return;
    }

    const login = await req('POST', '/api/user/login', { body: { username, password } });
    const token = JSON.parse(login.text).data.token;

    const start = await req('POST', '/api/psychological-chat/session/start', {
        token,
        body: { initialMessage: '你好', sessionTitle: '同日覆盖测试' }
    });
    const sessionId = JSON.parse(start.text).data.sessionId;
    console.log(`登录成功，会话 = ${sessionId}\n`);

    await resetToday(token);

    let d1;
    if (mode === 'prefilled') {
        // 模拟用户早上在前端表单里认真填了一条字段齐全的日记
        const body = {
            diaryDate: new Date().toISOString().slice(0, 10),
            moodScore: 6,
            dominantEmotion: '平静',
            emotionTriggers: '加班',
            diaryContent: '今天还行，就是有点累。',
            sleepQuality: 4,
            stressLevel: 2
        };
        const r = await req('POST', '/api/emotion-diary', { token, body });
        console.log('【表单预置】状态 ' + r.status + ' ' + r.text.slice(0, 100) + '\n');
        d1 = await todayDiary(token);
        show('预置后', d1);
        console.log();
    }
    else {
        // ---------- 早上 ----------
        const morning = mode === 'casual'
            // 用户啥细节都没给，只打个卡 —— 验证模型会不会自己编睡眠/压力/触发因素
            ? '帮我记一下，今天还行，就是有点累。'
            : '帮我记一下，今天早上特别倒霉，出门就下雨，上班还迟到了，心情很差，打个 2 分吧，主要情绪是悲伤，睡眠质量 2，压力 5，糟心的事就是迟到。';
        console.log('【早上】发送：' + morning);
        console.log('   AI 回复：' + await sendMessage(sessionId, morning, token) + '\n');
        d1 = await todayDiary(token);
        show('早上记完', d1);
        console.log();

        if (mode === 'casual') {
            console.log('========== 判定：用户没提到睡眠/压力/触发因素，这三个字段应该是 null ==========');
            const t = d1.today || {};
            console.log(`睡眠=${t.sleepQuality ?? 'null'}  压力=${t.stressLevel ?? 'null'}  触发因素=${t.emotionTriggers ?? 'null'}`);
            // 睡眠/压力是量表，用户没提就必须是 null；
            // 触发因素允许"从用户原话里摘"，但不允许凭空造 —— 所以检查它是不是出现在用户说过的话里
            const trigger = t.emotionTriggers ?? null;
            const triggerOk = trigger === null || morning.includes(trigger);
            const clean = (t.sleepQuality ?? null) === null && (t.stressLevel ?? null) === null && triggerOk;
            console.log(clean
                ? (trigger ? `✅ 睡眠/压力未填；触发因素"${trigger}"是用户原话里的，算摘录` : '✅ 没有瞎编，三个字段都保持未填写')
                : '❌ 模型编了用户没说过的值');
            return;
        }

        if (mode === 'dup') {
            // 同一句话再发一遍 —— 验证服务端的去重兜底（模型重试 / 用户手抖重复发）
            const once = String((d1.today || {}).diaryContent ?? '');
            console.log('【再说一遍】发送完全相同的一句话…');
            console.log('   AI 回复：' + await sendMessage(sessionId, morning, token) + '\n');
            const d2 = await todayDiary(token);
            const twice = show('重复后', d2);
            console.log('\n========== 判定 ==========');
            console.log(`正文长度：${once.length} → ${twice.length}`);
            const lines = twice.split('\n').filter(l => l.trim() !== '');
            const hasDupLine = lines.length !== new Set(lines).size;
            console.log('正文各行：');
            lines.forEach(l => console.log('   ' + l));
            console.log(hasDupLine
                ? '❌ 有整行重复 —— 去重没兜住'
                : (twice === once
                    ? '✅ 一字未多，去重生效'
                    : '✅ 没有重复内容（模型这次换了个更完整的说法，服务端用它替换了旧的那份）'));
            return;
        }
    }

    // ---------- 下午 ----------
    let sessionAfternoon = sessionId;
    if (mode === 'split' || mode === 'prefilled') {
        const start2 = await req('POST', '/api/psychological-chat/session/start', {
            token,
            body: { initialMessage: '你好', sessionTitle: '同日覆盖测试-下午新会话' }
        });
        sessionAfternoon = JSON.parse(start2.text).data.sessionId;
        console.log(`【下午换新会话】${sessionId} → ${sessionAfternoon}（历史里没有早上那轮了）\n`);
    }

    const afternoon = '下午好多了，因为项目评审顺利通过了，同事还请我喝了奶茶，心情一下子就好了。'
        + '帮我更新一下今天的记录，打 9 分，主要情绪是开心，触发原因就写「项目评审通过」。';
    console.log('【下午】发送：' + afternoon);
    console.log('   AI 回复：' + await sendMessage(sessionAfternoon, afternoon, token) + '\n');
    let d2 = await todayDiary(token);
    const after = show('下午记完', d2);
    console.log();

    // ---------- 判定 ----------
    const before = d1.today ? String(d1.today.diaryContent ?? '') : '';
    const trigBefore = d1.today ? String(d1.today.emotionTriggers ?? '') : '';
    const trigAfter = d2.today ? String(d2.today.emotionTriggers ?? '') : '';
    console.log('========== 判定 ==========');
    console.log(`日记总条数：${d1.total} → ${d2.total}  ${d1.total === d2.total ? '（始终一天一条，唯一索引没被撞）' : '（多插了一条）'}`);

    // 直接比对内容：原来的正文是不是还完整地留在新正文里
    const keptBefore = before !== '' && after.includes(before);
    const grew = after.length > before.length;

    console.log(`原有正文（${before.length} 字）是否完整保留：${keptBefore ? '✅ 是' : '❌ 被抹掉了'}`);
    console.log(`新正文是否变长了：${grew ? '✅ 是（说明追加进去了）' : '❌ 没变长'}`);

    // 触发因素同样按"追加"检查
    const trigKept = trigBefore === '' || trigAfter.includes(trigBefore);
    if (trigBefore !== '' || trigAfter !== '') {
        console.log(`\n触发因素：`);
        console.log(`   改前：${trigBefore || '（空）'}`);
        console.log(`   改后：${trigAfter || '（空）'}`);
        console.log(`   原有触发因素是否保留：${trigKept ? '✅ 是' : '❌ 被覆盖了'}`);
    }

    console.log(keptBefore && grew && trigKept
        ? '\n结论：服务端追加语义生效 —— 正文和触发因素都没丢，新内容接在后面。'
        : '\n结论：有内容被覆盖了。');
})();
