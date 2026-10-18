/**
 * 语法检查小工具：只解析、不执行。
 * 用途：改完前端 .js 后快速确认没有语法错误（不用起整个前端工程）。
 * 用法：node --experimental-vm-modules tools/syntax-check.js <文件路径>
 */
const fs = require('fs');
const vm = require('vm');

const file = process.argv[2];
if (!file) {
    console.log('用法：node --experimental-vm-modules tools/syntax-check.js <文件路径>');
    process.exit(1);
}

const src = fs.readFileSync(file, 'utf8');
try {
    // SourceTextModule 会做完整的语法解析，但不会执行
    new vm.SourceTextModule(src);
    console.log('✅ 语法 OK：' + file);
} catch (e) {
    console.log('❌ 语法错误：' + e.message);
    process.exit(1);
}
