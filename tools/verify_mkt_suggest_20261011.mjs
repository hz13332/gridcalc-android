// 2026-10-11 一次性验证脚本（任务 t2：补 8 只美股）
// 目的：不靠"肉眼看代码"，而是**从真实的 MktSuggest.kt 里解析出表**，再按源码里的匹配规则
//      模拟 [local] / [favLocal] / [rankFav] 三条路径，逐条断言 8 个代码与 6+ 个中文名能命中，
//      并做既有条目的回归（确认没把老条目挤掉、没把老查询污染）。
// 用法：node tools/verify_mkt_suggest_20261011.mjs
import { readFileSync } from "node:fs";

const SRC = new URL("../app/src/main/java/cn/gridcalc/gridcalc/MktSuggest.kt", import.meta.url);
const text = readFileSync(SRC, "utf8");

// ⚠ 2026-10-12 交接修正：**抓表前必须先剥掉整行 `//` 注释**，否则注释里被当作反例引用的
//    字面量会被引号正则当成真条目捞进来 —— 实测不剥注释时 `TOPS=31`（多了 MEITUAN/03690/加条目）、
//    `CNNAME=69`（多了注释里那句"故意不写"的 `"IBM" to "IBM"`），
//    于是假造出「搜 IBM 出两行」的幻影重复。剥完才是真值 `TOPS=28`、`CNNAME=68`。
const stripComments = (s) =>
  s.split("\n").filter((l) => !l.trim().startsWith("//")).join("\n");

/** 抓 `private val NAME = listOf( ... )` 的括号平衡内容（已剥注释） */
function grabBlock(name) {
  const i = text.indexOf(`val ${name} = listOf(`);
  if (i < 0) throw new Error(`找不到 ${name}`);
  let j = text.indexOf("(", i), depth = 0, start = -1;
  for (; j < text.length; j++) {
    if (text[j] === "(") { depth++; if (depth === 1) start = j + 1; }
    else if (text[j] === ")") { depth--; if (depth === 0) return stripComments(text.slice(start, j)); }
  }
  throw new Error(`${name} 括号不平衡`);
}
const strs = (b) => [...b.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
const pairs = (b) => [...b.matchAll(/"([^"]*)"\s*to\s*"([^"]*)"/g)].map((m) => [m[1], m[2]]);

const TOPC = strs(grabBlock("TOPC"));
const TOPM = pairs(grabBlock("TOPM"));
const TOPB = strs(grabBlock("TOPB"));
const TOPS = strs(grabBlock("TOPS"));
const CNNAME = pairs(grabBlock("CNNAME"));
const HKNAME = pairs(grabBlock("HKNAME"));
const KRNAME = pairs(grabBlock("KRNAME"));
const ENALIAS = pairs(grabBlock("ENALIAS"));

// ---- 源码规则复刻（MktSuggest.local / favLocal / rankFav / typeTag）----
// local:  CNNAME k.contains  →  TOPC startsWith → TOPM startsWith → TOPB startsWith
//         → TOPS startsWith → CNNAME v.startsWith(去重) → marketLocal → sortTop(取6)
// favLocal: TOPC/TOPM/TOPB/TOPS 全部 contains → CNNAME k.contains → CNNAME v.startsWith → marketLocal
// rankFav: s.contains(q) || q.contains(s) || tag.contains(q) || aliasTarget 命中
const uniqPush = (items, it) => { if (!items.some((x) => x.s === it.s)) items.push(it); };
const marketName = (s) =>
  (HKNAME.find((p) => p[0] === s) || KRNAME.find((p) => p[0] === s) || [])[1] || "";
const favName = (s) => (CNNAME.find((p) => p[1] === s) || [])[0] || marketName(s) || "";
const typeTag = () => "美股"; // 8 个新代码 symType 全落 stock（非 00Y/XAU/6位/4-5位/币对）

function marketLocal(q) {
  const items = [];
  for (const [k, v] of [...HKNAME, ...KRNAME]) {
    if (k.includes(q) || v.includes(q)) uniqPush(items, { s: k, tag: v, pre: k.startsWith(q) });
  }
  for (const [a, target] of ENALIAS) {
    if (a.includes(q)) uniqPush(items, { s: target, tag: favName(target) || a, pre: a.startsWith(q) });
  }
  return items;
}
const sortTop = (items) =>
  [...items].sort((a, b) => (b.pre ? 1 : 0) - (a.pre ? 1 : 0)).slice(0, 6);

function local(q) {
  const items = [];
  for (const [k, v] of CNNAME) if (k.includes(q)) items.push({ s: v, tag: k, pre: true });
  for (const f of TOPC) if (f.startsWith(q)) items.push({ s: f, tag: "币", pre: true });
  for (const [s, tag] of TOPM)
    if (s.startsWith(q) || (q.length === 1 && "黄金白银".includes(q) && tag.includes(q)))
      items.push({ s, tag, pre: s.startsWith(q) });
  for (const s of TOPB) if (s.startsWith(q)) items.push({ s, tag: "美债", pre: true });
  for (const s of TOPS) if (s.startsWith(q)) items.push({ s, tag: typeTag(s), pre: true });
  for (const [k, v] of CNNAME)
    if (v.startsWith(q) && !items.some((x) => x.s === v)) items.push({ s: v, tag: k, pre: true });
  for (const it of marketLocal(q)) uniqPush(items, it);
  // ⚠ 与源码 [local] 的 return 保持一字不差：同码只留首行（2026-10-12 源码补了 distinctBy，
  //   这里必须同步，否则模拟出来的行数/截断结果都对不上真机）。
  const seen = new Set();
  return sortTop(items.filter((it) => (seen.has(it.s) ? false : (seen.add(it.s), true))));
}
function favLocal(q) {
  const items = [];
  for (const f of TOPC) if (f.includes(q)) items.push({ s: f, tag: "币", pre: false });
  for (const [s, tag] of TOPM) if (s.includes(q)) items.push({ s, tag, pre: false });
  for (const s of TOPB) if (s.includes(q)) items.push({ s, tag: "美债", pre: false });
  for (const s of TOPS) if (s.includes(q)) items.push({ s, tag: typeTag(s), pre: false });
  for (const [k, v] of CNNAME) if (k.includes(q) && !items.some((x) => x.s === v)) items.push({ s: v, tag: k, pre: false });
  for (const [k, v] of CNNAME) if (v.startsWith(q) && !items.some((x) => x.s === v)) items.push({ s: v, tag: k, pre: false });
  for (const it of marketLocal(q)) uniqPush(items, it);
  return items;
}
function rankFav(items, q, mine = new Set()) {
  const rank = (x) => (x.s === q ? 0 : x.s.startsWith(q) ? 1 : 2);
  const aliasTarget = (ENALIAS.find((p) => p[0] === q || p[0].includes(q)) || [])[1];
  return items
    .filter((it) => (it.s.includes(q) || q.includes(it.s) || it.tag.includes(q) ||
      (aliasTarget != null && it.s === aliasTarget)) && !mine.has(it.s))
    .sort((a, b) => rank(a) - rank(b))
    .slice(0, 12);
}

// ---- 断言 ----
let pass = 0, fail = 0;
const ok = (cond, msg, extra = "") => { if (cond) { pass++; } else { fail++; console.log(`  ✗ ${msg} ${extra}`); } };
const show = (r) => r.map((x) => `${x.s}/${x.tag}`).join(" , ");

console.log(`解析：TOPS=${TOPS.length} 条  CNNAME=${CNNAME.length} 条  ENALIAS=${ENALIAS.length} 条  HKNAME=${HKNAME.length}  KRNAME=${KRNAME.length}`);

const CODES = ["SNDK", "ARM", "DELL", "IBM", "ASML", "AMAT", "LRCX", "KLAC"];
console.log("\n[1] 8 个代码：TOPS 成员 + local 前缀命中 + favLocal 包含命中 + rankFav 不被滤掉");
for (const c of CODES) {
  ok(TOPS.includes(c), `${c} 在 TOPS 里`);
  const l = local(c), f = favLocal(c);
  ok(l.some((x) => x.s === c), `local(${c}) 命中`, show(l));
  ok(f.some((x) => x.s === c), `favLocal(${c}) 命中`, show(f));
  ok(rankFav(f, c).some((x) => x.s === c), `rankFav 保留 ${c}`, show(rankFav(f, c)));
}

console.log("\n[2] 中文名：local(中文) + favLocal(中文) + rankFav(中文) 都落到正确代码");
const NAMES = [
  ["闪迪", "SNDK"], ["戴尔", "DELL"], ["戴尔科技", "DELL"],
  ["阿斯麦", "ASML"], ["阿斯麦尔", "ASML"], ["应用材料", "AMAT"],
  ["拉姆研究", "LRCX"], ["泛林集团", "LRCX"], ["泛林材料", "LRCX"],
  ["科磊", "KLAC"], ["安谋", "ARM"], ["ARM控股", "ARM"], ["国际商业机器", "IBM"],
];
for (const [q, want] of NAMES) {
  const l = local(q), f = favLocal(q);
  ok(l.some((x) => x.s === want), `local(${q}) → ${want}`, show(l));
  ok(f.some((x) => x.s === want), `favLocal(${q}) → ${want}`, show(f));
  ok(rankFav(f, q).some((x) => x.s === want), `rankFav(${q}) → ${want}`, show(rankFav(f, q)));
}

console.log("\n[3] 回归：既有条目一个都没丢，既有中文名照样命中");
const LEGACY_CN = ["美光", "苹果", "特斯拉", "英伟达", "微软", "亚马逊", "谷歌", "台积电",
  "阿里", "奈飞", "英特尔", "高通", "博通", "甲骨文", "拼多多", "京东", "理想", "蔚来",
  "小鹏", "摩根大通", "辉瑞", "可口可乐", "麦当劳", "迪士尼", "贝宝", "思科", "比特币",
  "黄金", "白银", "迈威尔", "长鑫存储", "长鑫科技", "GE Vernova", "通用电气韦诺瓦",
  "美债", "长期美债", "原油", "布伦特原油", "天然气", "燃油", "汽油", "铜", "铂金",
  "钯金", "玉米", "小麦", "大豆", "豆粕", "豆油", "燕麦", "稻谷", "棉花", "糖"];
for (const q of LEGACY_CN) {
  const want = (CNNAME.find((p) => p[0] === q) || [])[1];
  const l = local(q), f = favLocal(q);
  ok(l.some((x) => x.s === want), `local(${q}) 仍命中 ${want}`, show(l));
  ok(f.some((x) => x.s === want), `favLocal(${q}) 仍命中 ${want}`, show(f));
}
const LEGACY_CODES = ["AAPL", "TSLA", "NVDA", "MSFT", "AMZN", "META", "GOOGL", "AMD",
  "COIN", "MSTR", "NFLX", "BABA", "TSM", "PLTR", "GEV", "MRVL", "SPCX", "XOM", "GE", "TLT", "sh688825"];
for (const c of LEGACY_CODES) {
  ok(local(c).some((x) => x.s === c), `local(${c}) 仍命中`, show(local(c)));
}
// 港韩+别名回归
for (const q of ["TENCENT", "SAMSUNG", "KIA", "CXMT", "腾讯", "起亚", "长鑫"]) {
  const r = [...local(q), ...favLocal(q)];
  ok(r.length > 0, `${q} 仍能搜到`, show(r));
}

console.log("\n[4] 冲突/污染检查");
for (const c of CODES) {
  const hitAlias = ENALIAS.filter((p) => p[0].includes(c) || c.includes(p[0]));
  ok(hitAlias.length === 0, `${c} 与 ENALIAS 无子串关系`, JSON.stringify(hitAlias));
  const dup = TOPS.filter((x) => x === c).length;
  ok(dup === 1, `${c} 在 TOPS 里恰好 1 次`);
}
// 新中文名不得污染既有查询：搜既有名字时不得多出 8 个新代码
for (const q of LEGACY_CN) {
  const extra = [...local(q), ...favLocal(q)].filter((x) => CODES.includes(x.s));
  ok(extra.length === 0, `搜既有「${q}」不会冒出 8 个新代码`, show(extra));
}
// 搜新中文名不得冒出既有品种
for (const [q] of NAMES) {
  const extra = [...local(q), ...favLocal(q)].filter((x) => !CODES.includes(x.s) && !x.s.startsWith("sh"));
  ok(extra.length === 0, `搜新「${q}」不会冒出别的品种`, show(extra));
}
// 单字母 A 的截断行为（记录事实）
console.log("\n[5] 既有条目完整性：不靠手抄清单，直接从表里派生「非本次新增」的那部分逐条验");
const NEW_KEYS = new Set(NAMES.map((x) => x[0]));
const legacyKeys = CNNAME.map((p) => p[0]).filter((k) => !NEW_KEYS.has(k));
ok(legacyKeys.length === 55, `既有 CNNAME 键 = 55（新增 13，实得 ${legacyKeys.length}）`);
for (const k of legacyKeys) {
  const want = (CNNAME.find((p) => p[0] === k) || [])[1];
  ok(local(k).some((x) => x.s === want), `既有键「${k}」仍命中 ${want}`, show(local(k)));
  ok(favLocal(k).some((x) => x.s === want), `既有键「${k}」自选搜索仍命中 ${want}`, show(favLocal(k)));
}
const legacyTop = TOPS.filter((c) => !CODES.includes(c));
ok(legacyTop.length === 20, `既有 TOPS = 20（新增 8，实得 ${legacyTop.length}）`);
ok(CODES.every((c) => TOPS.filter((x) => x === c).length === 1), "8 个新代码在 TOPS 各恰好 1 次");
ok(CNNAME.length === 68, `CNNAME 总数 68（55 既有 + 13 新增，实得 ${CNNAME.length}）`);
ok(TOPS.length === 28, `TOPS 总数 28（20 既有 + 8 新增，实得 ${TOPS.length}）`);
// 新增中文名键 vs 既有中文名键：互不为子串（撞车检查从 [ENALIAS]/[TOPS] 扩到 [CNNAME]）
const subHit = [];
for (const k of NEW_KEYS) for (const e of legacyKeys) {
  if (e.includes(k) || k.includes(e)) subHit.push(`${k} ↔ ${e}`);
}
ok(subHit.length === 0, "13 个新中文名键与既有 CNNAME 键互不为子串", JSON.stringify(subHit));
// 既有键的**目标代码**不得被本批改动挪走
for (const k of legacyKeys) {
  ok(!CODES.includes((CNNAME.find((p) => p[0] === k) || [])[1]), `既有键「${k}」的目标代码未被改动`);
}

console.log("\n[6] 重复行（同码两行）—— 源码 [local] 的 return 已补 `distinctBy { it.s }`，" +
  "[favLocal] 的 CNNAME 循环本来就有守卫 ⟹ 两框都应为 0");
const dupOf = (r) => [...new Set(r.map((x) => x.s).filter((s, i, a) => a.indexOf(s) !== i))];
for (const q of ["A", "ARM", "戴尔", "阿斯麦", "泛林", "GE", "黄", "长鑫", "IBM", "国际商业机器"]) {
  console.log(`  local(${q}) 重复=${JSON.stringify(dupOf(local(q)))}   ` +
    `favLocal(${q}) 重复=${JSON.stringify(dupOf(favLocal(q)))}`);
}
// 用户实际会搜的 8 个代码 + 6 个必需中文名 + 全部多写法词 + 既有撞码词，两条路径都不许重复行
const DUP_Q = [...CODES, "闪迪", "戴尔", "阿斯麦尔", "应用材料", "泛林材料", "科磊",
  "戴尔科技", "阿斯麦", "拉姆研究", "泛林集团", "泛林", "安谋", "ARM控股", "GE", "黄", "长鑫"];
for (const q of DUP_Q) {
  ok(dupOf(local(q)).length === 0, `local(${q}) 无重复行`, show(local(q)));
  ok(dupOf(favLocal(q)).length === 0, `favLocal(${q}) 无重复行`, show(favLocal(q)));
}
// 去重不得让任何一条命中的行消失：同一批查询必须仍命中目标代码
for (const q of ["A", "ARM", "戴尔", "阿斯麦", "泛林", "GE", "黄", "长鑫"]) {
  const want = q === "A" || q === "ARM" ? "ARM"
    : q === "戴尔" ? "DELL" : q === "阿斯麦" ? "ASML"
    : q.startsWith("泛林") ? "LRCX" : q === "GE" ? "GEV"
    : q === "黄" ? "XAUUSD" : "sh688825";
  ok(local(q).some((x) => x.s === want), `去重后 local(${q}) 仍命中 ${want}`, show(local(q)));
}

console.log(`\n结果：pass=${pass}  fail=${fail}`);
process.exit(fail === 0 ? 0 : 1);
