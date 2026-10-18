/**
 * 情绪日记链路探针：登录 → 保存日记 → AI 分析
 * 用法：node tools/diary-probe.js <用户名> <密码>
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
    console.log(r.text);
}

(async () => {
    const [, , username, password] = process.argv;
    if (!username || !password) {
        console.log('用法：node tools/diary-probe.js <用户名> <密码>');
        return;
    }

    // 1. 登录
    const login = await req('POST', '/api/user/login', { body: { username, password } });
    show('① 登录', login);

    const loginJson = JSON.parse(login.text);
    const token = loginJson.data && loginJson.data.token;
    if (!token) {
        console.log('\n没拿到 token，后面的步骤做不了。');
        return;
    }

    // 2. 保存一篇日记
    const diary = {
        moodScore: 3,
        dominantEmotion: '焦虑',
        emotionTriggers: '明天要交项目，进度还差一大截',
        diaryContent: '今天一整天都在赶进度，越赶越乱。晚上躺在床上翻来覆去睡不着，'
                + '脑子里全是没做完的事。有点怀疑自己是不是根本做不好。',
        sleepQuality: 2,
        stressLevel: 5
    };
    const save = await req('POST', '/api/emotion-diary', { token, body: diary });
    show('② 保存日记', save);

    const saveJson = JSON.parse(save.text);
    const diaryId = saveJson.data && saveJson.data.id;
    if (!diaryId) {
        console.log('\n没拿到日记 id，分析做不了。');
        return;
    }
    console.log(`\n>>> 拿到日记 id = ${diaryId}`);

    // 3. 触发 AI 分析
    const analyze = await req('POST', `/api/emotion-diary/${diaryId}/analyze`, { token });
    show('③ AI 情绪分析', analyze);

    // 4. 我的日记列表（应该只包含当前用户的，且带 aiAnalysis）
    const myList = await req('GET', '/api/emotion-diary/my?current=1&size=5', { token });
    console.log('\n===== ④ 我的日记列表 =====');
    console.log(`HTTP ${myList.status}`);
    const page = JSON.parse(myList.text).data;
    console.log(`total = ${page.total}，本页 ${page.records.length} 条`);
    page.records.forEach(d => {
        console.log(`  id=${d.id}  userId=${d.userId}  ${d.diaryDate}  ${d.moodScore}分  ${d.dominantEmotion}`
                + `  AI分析=${d.aiAnalysis ? d.aiAnalysis.primaryEmotion + '/risk=' + d.aiAnalysis.riskLevel : '无'}`);
    });
})();
