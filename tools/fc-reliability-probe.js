/**
 * 探针：Function Calling 到底会不会"嘴上说记了、实际没调工具"？
 * 用法：node tools/fc-reliability-probe.js <用户名> <密码> [已存在的会话ID] [轮数，默认 5]
 *
 * ★ 一个方法论上的坑，值得记下来：
 *   我第一版探针在消息里塞了一个随机数字当标记（"帮我记一下：长会话标记123456"），
 *   结果模型反而不上当了 —— 它说"这看起来像测试标记，你想原样记进去吗？"，主动来确认。
 *   **测试数据本身改变了被测行为**，于是那个真实的 bug 根本复现不出来。
 *
 *   所以现在改成：发**完全自然的日常语句**（今天吃了什么），
 *   靠"日记正文有没有变化"来判断工具有没有真的执行，
 *   而不是靠一个会让模型起疑的标记。
 */

const http = require('http');

const HOST = '127.0.0.1';
const PORT = Number(process.env.PROBE_PORT || 8080);

/**
 * 一组自然的日常句子，每句都带一个**独特的词**（食物/事物名）。
 *
 * ★ 为什么要用独一无二的词做判定：
 *   1. 日记写入有去重（同一段话不重复追加），重复发同一句时正文本来就不会变 ——
 *      拿"正文变长"当判据，会把"去重生效"误读成"工具没执行"。
 *   2. `updated_at` 是秒级精度，两轮落在同一秒里字符串就一样 ——
 *      拿它当判据同样会误判。
 *   所以这里改成：发一句带独特词的日常话，然后**只在正文里找那个词**。
 *   词是随机挑的、而且会跳过已经出现过的，判定就是干净的。
 */
const THINGS = [
    '红烧排骨', '手冲咖啡', '麻辣烫', '橘猫', '抹茶千层', '提拉米苏', '杨枝甘露',
    '芝士蛋挞', '焦糖布丁', '舒芙蕾', '红豆双皮奶', '照烧鸡腿', '番茄牛腩',
    '糖醋里脊', '黑森林蛋糕', '草莓慕斯', '芒果班戟', '榴莲酥', '肉松小贝',
    '螺蛳粉', '酸辣粉', '煎饺', '小笼包', '烤冷面', '驴打滚', '龟苓膏'
];

const TEMPLATES = [
    t => `帮我记一下，今晚我吃了${t}，很满足`,
    t => `帮我记一下，今天吃到了${t}，心情不错`,
    t => `帮我记一笔，中午吃的${t}，挺开心的`
];

/** 从池子里挑一个"正文里还没出现过"的词，保证判定干净 */
function pickFreshThing(content) {
    const candidates = THINGS.filter(t => !content.includes(t));
    if (candidates.length === 0) {
        throw new Error('词池用完了，换一批 THINGS 再跑');
    }
    return candidates[Math.floor(Math.random() * candidates.length)];
}

/** 另一种风格：情绪饱满 + 带明确分数（跟 fc-probe 用的那种接近） */
const RICH_CASES = [
    '帮我把今天的感受记下来。我今天特别焦虑，因为明天要交论文，昨晚也没睡好，打个3分吧，主要情绪是焦虑。',
    '帮我记一笔，今天一整天都提不起劲，什么都不想干，打4分，情绪是疲惫。',
    '帮我记一下今天，下午跟朋友聊了很久，整个人轻松多了，打8分，主要情绪是开心。',
    '帮我记一下，今天被领导当众批评了，特别难受，打2分，情绪是悲伤。',
    '帮我记一下，今天睡得很好，早上起来神清气爽，打9分，主要情绪是平静。'
];

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

/**
 * 取今天那条日记。
 *
 * ★ 返回 updatedAt 是必须的：正文长度不能当作"工具有没有执行"的判据 ——
 *   工具内部有去重（同一段话不重复追加），所以重复发同一句时正文不变，
 *   但那恰恰说明**工具执行成功了**。用 updatedAt 才准：每执行一次都会刷新。
 *
 *   （这个坑我踩了：第一版探针只看正文长度，把"去重生效"误判成了"模型幻觉"。）
 */
async function todayDiary(token) {
    const r = await req('GET', '/api/emotion-diary/my?current=1&size=5', { token });
    const page = JSON.parse(r.text).data;
    const today = new Date().toISOString().slice(0, 10);
    const item = page.records.find(d => String(d.diaryDate).startsWith(today));
    return item
        ? { content: String(item.diaryContent ?? ''), updatedAt: String(item.updatedAt ?? '') }
        : { content: '', updatedAt: '' };
}

async function newSession(token, title) {
    const r = await req('POST', '/api/psychological-chat/session/start', {
        token,
        body: { initialMessage: '你好', sessionTitle: title }
    });
    return JSON.parse(r.text).data.sessionId;
}

/** 模型是不是在**声称已经完成**（过去式），而不是"我这就帮你记"（未来式） */
function claimsDone(reply) {
    return /已经.{0,8}(记|写|存)/.test(reply) || /已记录|已写入|记好了/.test(reply);
}

/** 跑一组对照 */
async function runGroup(label, sessionId, token, rounds) {
    console.log(`\n========== ${label}（会话 ${sessionId}）==========`);
    let honest = 0, hallucinated = 0, refused = 0;
    // PROBE_FRESH_EACH=1 时，每一轮都开一个全新会话 —— 用来排除"历史消息"这个变量
    const freshEach = process.env.PROBE_FRESH_EACH === '1';

    for (let i = 0; i < rounds; i++) {
        if (freshEach) {
            const r = await req('POST', '/api/psychological-chat/session/start', {
                token,
                body: { initialMessage: '你好', sessionTitle: `可靠性-${label}-${i + 1}` }
            });
            sessionId = JSON.parse(r.text).data.sessionId;
        }
        const before = await todayDiary(token);

        // 用"正文里还没出现过的独特词"造这一轮的消息，判定才干净
        const thing = pickFreshThing(before.content);
        const message = (process.env.PROBE_STYLE === 'rich' ? TEMPLATES[2] : TEMPLATES[i % TEMPLATES.length])(thing);

        const reply = await sendMessage(sessionId, message, token);
        const after = await todayDiary(token);

        // ★ 判定依据：那个独特的词有没有真的出现在日记正文里
        const wroteNewContent = after.content.includes(thing);
        // updated_at 只当辅助信号（秒级精度，同秒内会看不出变化）
        const touched = after.updatedAt !== before.updatedAt;
        const claimed = claimsDone(reply);

        let tag;
        if (wroteNewContent) {
            tag = '✅ 工具真的写了，日记里能查到那个词';
            honest++;
        } else if (claimed) {
            tag = '❌❌ 幻觉：嘴上说"已经记好了"，但工具压根没执行';
            hallucinated++;
        } else {
            tag = '⚠️  工具没执行，但也没撒谎（追问/拒绝）';
            refused++;
        }

        console.log(`\n  [${i + 1}/${rounds}] ${message}`);
        console.log(`     会话：${sessionId}`);
        console.log(`     回复：${reply.replace(/\s+/g, ' ').slice(0, 90)}…`);
        console.log(`     找的词：${thing}   正文：${before.content.length} → ${after.content.length} 字   updatedAt${touched ? '有变' : '未变（同秒）'}`);
        console.log(`     ${tag}`);
    }

    console.log(`\n  —— ${label} 小计：成功 ${honest} / 幻觉 ${hallucinated} / 追问 ${refused}（共 ${rounds} 轮）`);
    return { honest, hallucinated, refused };
}

(async () => {
    const [, , username, password, longSession, roundsArg] = process.argv;
    if (!username || !password) {
        console.log('用法：node tools/fc-reliability-probe.js <用户名> <密码> [已存在的会话ID] [轮数，默认5]');
        return;
    }
    const rounds = Number(roundsArg) || 5;

    const login = await req('POST', '/api/user/login', { body: { username, password } });
    const token = JSON.parse(login.text).data.token;

    const longResult = longSession
        ? await runGroup('A 长历史会话', longSession, token, rounds)
        : { honest: 0, hallucinated: 0, refused: 0 };

    const fresh = await newSession(token, '可靠性测试-新会话');
    const freshResult = await runGroup('B 全新会话', fresh, token, rounds);

    console.log('\n========== 结论 ==========');
    if (longSession) {
        console.log(`长历史会话：成功 ${longResult.honest}，幻觉 ${longResult.hallucinated}，追问 ${longResult.refused}`);
    }
    console.log(`全新会话  ：成功 ${freshResult.honest}，幻觉 ${freshResult.hallucinated}，追问 ${freshResult.refused}`);
    console.log('\n只要"幻觉"不为 0，就说明模型会在没调用工具的情况下声称已经记录 ——');
    console.log('对用户来说这是最糟的失败方式：他以为记上了，其实什么都没有。');
})();
