#!/usr/bin/env node
/**
 * 商品配图生成器 —— 本地 SVG 取代 picsum.photos 随机外链。
 *
 * 原来的种子数据把商品图/评价图指向 picsum 的随机照片：卖保健品的货架上摆着
 * 风景、猫和建筑工地，评价区里是陌生人的旅游照。图能显示，但页面读起来不像
 * 一家真实店铺——而且每次演示都依赖外网，离线直接挂一片。
 *
 * 这里按设计系统的色板（陶土橙 / 青绿 / 暖灰）程序化生成配图。
 *
 * **文件名 = 原 URL 里的 seed-尺寸**（`seed/spu1/600` → `spu1-600.svg`），
 * 于是数据库侧的迁移没有任何逐条映射表，只是一条通配替换规则。
 *
 * 生成是幂等的：不使用随机数，同一份输入每次产出完全一致。
 * 运行前会拿两份 data.sql 里的 /img/photos 引用与清单对账，对不上直接报错——
 * 避免「加了 SKU 忘了配图」这种要等到演示当场才发现的缺口。
 *
 * 用法：node scripts/gen-product-images.mjs
 */
import { mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const OUT = join(here, '..', 'public', 'img')
const PRODUCT_SQL = join(here, '..', '..', 'backend', 'product-service', 'src', 'main', 'resources', 'data.sql')
const REVIEW_SQL = join(here, '..', '..', 'backend', 'review-service', 'src', 'main', 'resources', 'data.sql')

// ==================== 色板（与 tokens.css 同源） ====================
const BRAND = {
  1: { name: '益生源', c: '#1f7a6b', deep: '#14564b', tint: '#d7efe8' },   // 青绿：肠道/益生菌
  2: { name: '元气元素', c: '#b65b2e', deep: '#8c3c18', tint: '#f9e8dc' }, // 陶土橙：维生素
  3: { name: '肌力方', c: '#b7791f', deep: '#92610e', tint: '#fdf7ec' },   // 琥珀：运动营养
  4: { name: '康倍适', c: '#7d6b52', deep: '#4a3a29', tint: '#efe9dd' },   // 暖褐：特医
  5: { name: '深海鲜', c: '#3a6b8a', deep: '#274d66', tint: '#dde9f0' },   // 海蓝：海洋来源
  6: { name: '本草纪', c: '#2f855a', deep: '#276749', tint: '#eefaf3' },   // 草绿：草本
}

// 内容物观感：软胶囊的琥珀、片剂的米白、软糖的水果色……决定画面里「装的是什么」
const CONTENT = {
  'softgel-amber':  { kind: 'softgel', a: '#e0a95c', b: '#b8752a' },
  'softgel-golden': { kind: 'softgel', a: '#e6c069', b: '#c08d2c' },
  'softgel-clear':  { kind: 'softgel', a: '#f0d9a8', b: '#cfa855' },
  'capsule-white':  { kind: 'capsule', a: '#f7f2e8', b: '#e3d9c6' },
  'tablet-white':   { kind: 'tablet',  a: '#faf6ee', b: '#e6dcc9' },
  'gummy-multi':    { kind: 'gummy',   colors: ['#e8874a', '#c9524a', '#8e5aa8', '#e8b93f', '#d96f8a'] },
  'gummy-amber':    { kind: 'gummy',   colors: ['#d98a4a', '#e0a95c', '#c9762f'] },
  'powder-cream':   { kind: 'powder',  a: '#f0e6d2', b: '#d9c9a8' },
}

/*
 * 商品清单。label 是印在瓶贴上的商品名——手工断行而不是让代码去猜，
 * 因为「复合维生素 / 矿物质片」和「儿童多种维生素 / 软糖」这类断法
 * 只有排版常识判得准。
 */
const PRODUCTS = [
  { id: 1,  brand: 2, kind: 'bottle', contents: 'softgel-amber',  label: ['维生素 D3', '软胶囊'],   gallery: ['pair', 'spread', 'macro'] },
  { id: 2,  brand: 2, kind: 'bottle', contents: 'tablet-white',   label: ['复合维生素', '矿物质片'] },
  { id: 3,  brand: 3, kind: 'tub',    contents: 'powder-cream',   label: ['乳清蛋白粉'] },
  { id: 4,  brand: 3, kind: 'tub',    contents: 'powder-cream',   label: ['植物蛋白粉'] },
  { id: 5,  brand: 1, kind: 'sachet', contents: null,             label: ['益生菌粉'] },
  { id: 6,  brand: 1, kind: 'tub',    contents: 'powder-cream',   label: ['膳食纤维粉'] },
  { id: 7,  brand: 5, kind: 'bottle', contents: 'softgel-golden', label: ['鱼油', '软胶囊'] },
  { id: 8,  brand: 5, kind: 'bottle', contents: 'softgel-golden', label: ['辅酶 Q10', '软胶囊'] },
  { id: 9,  brand: 6, kind: 'bottle', contents: 'softgel-clear',  label: ['维生素 K2', '软胶囊'] },
  { id: 10, brand: 2, kind: 'bottle', contents: 'tablet-white',   label: ['碳酸钙 D3', '咀嚼片'] },
  { id: 11, brand: 2, kind: 'bottle', contents: 'capsule-white',  label: ['柠檬酸钙', '胶囊'] },
  { id: 12, brand: 4, kind: 'box',    contents: null,             label: ['孕期', '复合营养包'] },
  { id: 13, brand: 4, kind: 'tub',    contents: 'powder-cream',   label: ['全营养', '配方粉'] },
  { id: 14, brand: 6, kind: 'tub',    contents: 'powder-cream',   label: ['胶原蛋白肽粉'] },
  { id: 15, brand: 2, kind: 'jar',    contents: 'gummy-multi',    label: ['儿童多种维生素', '软糖'] },
  { id: 16, brand: 2, kind: 'bottle', contents: 'tablet-white',   label: ['褪黑素', '缓释片'] },
  { id: 17, brand: 6, kind: 'jar',    contents: 'gummy-amber',    label: ['γ-氨基丁酸', '软糖'] },
  { id: 18, brand: 5, kind: 'bottle', contents: 'softgel-amber',  label: ['叶黄素酯', '软胶囊'] },
  { id: 19, brand: 6, kind: 'bottle', contents: 'tablet-white',   label: ['越橘', '叶黄素片'] },
  { id: 20, brand: 2, kind: 'bottle', contents: 'tablet-white',   label: ['维生素 C', '咀嚼片'] },
  { id: 21, brand: 2, kind: 'bottle', contents: 'tablet-white',   label: ['锌硒宝片'] },
  { id: 22, brand: 3, kind: 'bottle', contents: 'softgel-amber',  label: ['共轭亚油酸', '软胶囊'] },
  { id: 23, brand: 6, kind: 'bottle', contents: 'tablet-white',   label: ['白芸豆', '膳食纤维片'] },
  { id: 24, brand: 4, kind: 'bottle', contents: 'tablet-white',   label: ['氨糖软骨素', '钙片'] },
  { id: 25, brand: 4, kind: 'bottle', contents: 'tablet-white',   label: ['孕妇钙片'] },
  { id: 26, brand: 2, kind: 'jar',    contents: 'gummy-multi',    label: ['儿童钙软糖'] },
  { id: 27, brand: 4, kind: 'bottle', contents: 'softgel-amber',  label: ['孕期 DHA', '藻油软胶囊'] },
]

const product = (id) => PRODUCTS.find((p) => p.id === id)
const brandOf = (p) => BRAND[p.brand]
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const FONT = "'Noto Sans SC','Microsoft YaHei','PingFang SC',sans-serif"

// ==================== 画面基座 ====================

/** 商品图背景：暖底 + 品牌色一角柔光 + 细点纹理 + 装饰圆环。内容物渐变一并定义，全文档共用一枚 id */
function backdrop(p) {
  const b = brandOf(p)
  const c = p.contents ? CONTENT[p.contents] : null
  const grad = c && (c.kind === 'softgel' || c.kind === 'capsule')
    ? `<linearGradient id="cg" x1="0" y1="0" x2="0.6" y2="1">
         <stop offset="0" stop-color="${c.a}"/><stop offset="1" stop-color="${c.b}"/>
       </linearGradient>`
    : ''
  return `
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#fbf8f3"/><stop offset="1" stop-color="#f1eadc"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.8" cy="0.12" r="0.85">
      <stop offset="0" stop-color="${b.c}" stop-opacity="0.22"/>
      <stop offset="1" stop-color="${b.c}" stop-opacity="0"/>
    </radialGradient>
    <pattern id="dots" width="26" height="26" patternUnits="userSpaceOnUse">
      <circle cx="2" cy="2" r="1.1" fill="${b.deep}" opacity="0.10"/>
    </pattern>
    <linearGradient id="body" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#f3ecdf"/>
    </linearGradient>
    <linearGradient id="brandfill" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${b.c}"/><stop offset="1" stop-color="${b.deep}"/>
    </linearGradient>
    ${grad}
  </defs>
  <rect width="600" height="600" fill="url(#bg)"/>
  <rect width="600" height="600" fill="url(#dots)"/>
  <rect width="600" height="600" fill="url(#glow)"/>
  <circle cx="86" cy="512" r="150" fill="none" stroke="${b.c}" stroke-opacity="0.12" stroke-width="26"/>`
}

const groundShadow = (cx, cy, rx) =>
  `<ellipse cx="${cx}" cy="${cy}" rx="${rx}" ry="${rx * 0.15}" fill="#4a3a29" opacity="0.10"/>`

// ==================== 内容物（单件，供散落/平铺/特写场景复用） ====================

function drawContent(type, x, y, rot = 0, scale = 1) {
  const c = CONTENT[type]
  const g = (inner) => `<g transform="translate(${x} ${y}) rotate(${rot}) scale(${scale})">${inner}</g>`
  switch (c.kind) {
    case 'softgel':
      return g(`<rect x="-19" y="-27" width="38" height="54" rx="19" fill="url(#cg)"/>
        <ellipse cx="-6" cy="-13" rx="6" ry="9" fill="#ffffff" opacity="0.55"/>`)
    case 'capsule':
      return g(`<rect x="-14" y="-27" width="28" height="54" rx="14" fill="url(#cg)"/>
        <line x1="-12" y1="0" x2="12" y2="0" stroke="${c.b}" stroke-width="1.6"/>
        <ellipse cx="-5" cy="-14" rx="4" ry="7" fill="#ffffff" opacity="0.7"/>`)
    case 'tablet':
      return g(`<circle r="21" fill="${c.a}" stroke="${c.b}" stroke-width="2"/>
        <line x1="-13" y1="0" x2="13" y2="0" stroke="${c.b}" stroke-width="2" stroke-linecap="round"/>
        <ellipse cx="-7" cy="-8" rx="7" ry="5" fill="#ffffff" opacity="0.6"/>`)
    case 'gummy':
      return g(`<rect x="-22" y="-22" width="44" height="44" rx="15" fill="${c.colors[0]}" opacity="0.92"/>
        <ellipse cx="-8" cy="-9" rx="9" ry="6" fill="#ffffff" opacity="0.5"/>
        <rect x="-16" y="-16" width="32" height="32" rx="11" fill="none" stroke="#ffffff" stroke-opacity="0.35" stroke-width="2"/>`)
    case 'powder':
      return g(`<path d="M-60 0 Q0 -46 60 0 Z" fill="${c.a}" stroke="${c.b}" stroke-width="1.5"/>
        <circle cx="-20" cy="-10" r="2.2" fill="${c.b}" opacity="0.5"/>
        <circle cx="14" cy="-16" r="2" fill="${c.b}" opacity="0.5"/>
        <circle cx="32" cy="-6" r="2.4" fill="${c.b}" opacity="0.45"/>`)
    default:
      return ''
  }
}

// ==================== 容器（不含地面阴影，由场景决定摆位） ====================

function drawBottle(p) {
  const b = brandOf(p)
  const two = p.label.length === 2
  return `
  <g>
    <rect x="234" y="112" width="132" height="74" rx="14" fill="url(#brandfill)"/>
    ${[0, 1, 2, 3, 4, 5, 6].map((i) => `<line x1="${244 + i * 19}" y1="122" x2="${244 + i * 19}" y2="176" stroke="#ffffff" stroke-opacity="0.18" stroke-width="3"/>`).join('')}
    <rect x="248" y="182" width="104" height="42" fill="#efe7d9"/>
    <rect x="180" y="218" width="240" height="288" rx="26" fill="url(#body)" stroke="#e5dccb" stroke-width="2"/>
    <rect x="196" y="284" width="208" height="158" rx="10" fill="#ffffff" stroke="#eee5d4" stroke-width="1.5"/>
    <rect x="196" y="284" width="208" height="8" rx="4" fill="${b.c}"/>
    <text x="300" y="${two ? 342 : 358}" text-anchor="middle" font-family="${FONT}" font-size="30" font-weight="600" fill="#2f2418">${esc(p.label[0])}</text>
    ${two ? `<text x="300" y="382" text-anchor="middle" font-family="${FONT}" font-size="27" font-weight="600" fill="#2f2418">${esc(p.label[1])}</text>` : ''}
    <line x1="238" y1="${two ? 402 : 386}" x2="362" y2="${two ? 402 : 386}" stroke="#e8ddc9" stroke-width="1.5"/>
    <text x="300" y="${two ? 426 : 410}" text-anchor="middle" font-family="${FONT}" font-size="18" fill="${b.deep}" letter-spacing="3">${esc(b.name)}</text>
    ${p.contents ? drawContent(p.contents, 148, 480, -22, 1) : ''}
    ${p.contents ? drawContent(p.contents, 455, 468, 18, 0.95) : ''}
  </g>`
}

function drawTub(p) {
  const b = brandOf(p)
  const two = p.label.length === 2
  return `
  <g>
    <rect x="138" y="146" width="324" height="76" rx="18" fill="url(#brandfill)"/>
    ${[0, 1, 2, 3, 4, 5, 6, 7, 8].map((i) => `<line x1="${152 + i * 34}" y1="156" x2="${152 + i * 34}" y2="212" stroke="#ffffff" stroke-opacity="0.16" stroke-width="4"/>`).join('')}
    <rect x="156" y="208" width="288" height="302" rx="28" fill="url(#body)" stroke="#e5dccb" stroke-width="2"/>
    <rect x="176" y="264" width="248" height="172" rx="10" fill="#ffffff" stroke="#eee5d4" stroke-width="1.5"/>
    <rect x="176" y="264" width="248" height="8" rx="4" fill="${b.c}"/>
    <text x="300" y="${two ? 330 : 350}" text-anchor="middle" font-family="${FONT}" font-size="${two ? 31 : 33}" font-weight="600" fill="#2f2418">${esc(p.label[0])}</text>
    ${two ? `<text x="300" y="374" text-anchor="middle" font-family="${FONT}" font-size="28" font-weight="600" fill="#2f2418">${esc(p.label[1])}</text>` : ''}
    <line x1="222" y1="${two ? 394 : 380}" x2="378" y2="${two ? 394 : 380}" stroke="#e8ddc9" stroke-width="1.5"/>
    <text x="300" y="${two ? 418 : 404}" text-anchor="middle" font-family="${FONT}" font-size="18" fill="${b.deep}" letter-spacing="3">${esc(b.name)}</text>
    ${drawContent('powder-cream', 96, 500, 0, 0.55)}
    <g transform="translate(500 486) rotate(14)">
      <ellipse cx="0" cy="0" rx="26" ry="16" fill="#f4eee0" stroke="#ddd0b8" stroke-width="2"/>
      <path d="M20 -8 L52 -26" stroke="#ddd0b8" stroke-width="7" stroke-linecap="round"/>
    </g>
  </g>`
}

function drawJar(p) {
  const b = brandOf(p)
  const c = CONTENT[p.contents]
  const two = p.label.length === 2
  const gummies = [
    { x: 226, y: 336, r: -14, c: c.colors[0] },
    { x: 300, y: 320, r: 10, c: c.colors[1 % c.colors.length] },
    { x: 372, y: 340, r: -6, c: c.colors[2 % c.colors.length] },
    { x: 258, y: 392, r: 18, c: c.colors[3 % c.colors.length] },
    { x: 340, y: 388, r: -20, c: c.colors[4 % c.colors.length] },
  ]
  return `
  <g>
    <rect x="176" y="252" width="248" height="252" rx="26" fill="${b.c}" opacity="0.14"/>
    <rect x="176" y="252" width="248" height="252" rx="26" fill="#ffffff" opacity="0.45" stroke="#e5dccb" stroke-width="2"/>
    ${gummies.map((g) => `<g transform="translate(${g.x} ${g.y}) rotate(${g.r})" opacity="0.9">
        <rect x="-23" y="-23" width="46" height="46" rx="16" fill="${g.c}"/>
        <ellipse cx="-8" cy="-10" rx="9" ry="6" fill="#ffffff" opacity="0.45"/>
      </g>`).join('')}
    <rect x="168" y="206" width="264" height="58" rx="18" fill="url(#brandfill)"/>
    <ellipse cx="300" cy="206" rx="132" ry="22" fill="${b.c}"/>
    <ellipse cx="300" cy="202" rx="112" ry="15" fill="#ffffff" opacity="0.16"/>
    <rect x="206" y="392" width="188" height="96" rx="10" fill="#ffffff" opacity="0.95" stroke="#eee5d4" stroke-width="1.5"/>
    <text x="300" y="${two ? 424 : 436}" text-anchor="middle" font-family="${FONT}" font-size="${two ? 23 : 25}" font-weight="600" fill="#2f2418">${esc(p.label[0])}</text>
    ${two ? `<text x="300" y="452" text-anchor="middle" font-family="${FONT}" font-size="21" font-weight="600" fill="#2f2418">${esc(p.label[1])}</text>` : ''}
    <text x="300" y="${two ? 474 : 466}" text-anchor="middle" font-family="${FONT}" font-size="14" fill="${b.deep}" letter-spacing="2">${esc(b.name)}</text>
  </g>`
}

function drawSachet(p) {
  const b = brandOf(p)
  return `
  <g>
    <g transform="translate(438 452) rotate(-64)">
      <rect x="-72" y="-132" width="144" height="264" rx="16" fill="#f6f0e2" stroke="#e3d9c6" stroke-width="2"/>
      <rect x="-72" y="-132" width="144" height="52" rx="16" fill="${b.c}" opacity="0.85"/>
      <rect x="-46" y="-40" width="92" height="12" rx="6" fill="#d9cbb2"/>
      <rect x="-46" y="-14" width="64" height="12" rx="6" fill="#e2d7c2"/>
    </g>
    <rect x="212" y="128" width="176" height="384" rx="18" fill="url(#body)" stroke="#e3d9c6" stroke-width="2"/>
    <rect x="212" y="128" width="176" height="86" rx="18" fill="url(#brandfill)"/>
    ${[0, 1, 2].map((i) => `<line x1="${266 + i * 34}" y1="118" x2="${266 + i * 34}" y2="140" stroke="#c9bca6" stroke-width="3"/>`).join('')}
    <text x="300" y="188" text-anchor="middle" font-family="${FONT}" font-size="26" font-weight="600" fill="#ffffff">${esc(b.name)}</text>
    <text x="300" y="292" text-anchor="middle" font-family="${FONT}" font-size="30" font-weight="600" fill="#2f2418">${esc(p.label[0])}</text>
    <line x1="248" y1="322" x2="352" y2="322" stroke="#e8ddc9" stroke-width="1.5"/>
    <text x="300" y="352" text-anchor="middle" font-family="${FONT}" font-size="19" fill="#7d6b52">独立包装 · 便携分装</text>
    <rect x="240" y="408" width="120" height="64" rx="10" fill="${b.tint}" opacity="0.8"/>
    <text x="300" y="448" text-anchor="middle" font-family="${FONT}" font-size="20" fill="${b.deep}">颗粒</text>
  </g>`
}

function drawBox(p) {
  const b = brandOf(p)
  return `
  <g>
    <polygon points="170,196 254,152 470,152 388,196" fill="#ffffff" stroke="#e5dccb" stroke-width="2"/>
    <polygon points="388,196 470,152 470,468 388,510" fill="${b.tint}" stroke="#e0d5c2" stroke-width="2"/>
    <rect x="170" y="196" width="218" height="314" fill="url(#body)" stroke="#e5dccb" stroke-width="2"/>
    <rect x="170" y="196" width="218" height="64" fill="url(#brandfill)"/>
    <text x="279" y="237" text-anchor="middle" font-family="${FONT}" font-size="24" font-weight="600" fill="#ffffff" letter-spacing="2">${esc(b.name)}</text>
    <text x="279" y="330" text-anchor="middle" font-family="${FONT}" font-size="31" font-weight="600" fill="#2f2418">${esc(p.label[0])}</text>
    <text x="279" y="370" text-anchor="middle" font-family="${FONT}" font-size="27" font-weight="600" fill="#2f2418">${esc(p.label[1] ?? '')}</text>
    <line x1="215" y1="394" x2="343" y2="394" stroke="#e8ddc9" stroke-width="1.5"/>
    <rect x="231" y="418" width="96" height="40" rx="20" fill="${b.tint}"/>
    <text x="279" y="444" text-anchor="middle" font-family="${FONT}" font-size="18" fill="${b.deep}">营养包</text>
    <g transform="translate(120 448) rotate(-10)">
      <rect x="-32" y="-52" width="64" height="104" rx="12" fill="#faf6ee" stroke="#e3d9c6" stroke-width="2"/>
      <rect x="-32" y="-52" width="64" height="26" rx="12" fill="${b.c}" opacity="0.85"/>
    </g>
  </g>`
}

const CONTAINER = { bottle: drawBottle, tub: drawTub, jar: drawJar, sachet: drawSachet, box: drawBox }

// ==================== 场景 ====================

function sceneHero(p) {
  return svgDoc(`${backdrop(p)}
  ${groundShadow(300, 512, 168)}
  ${CONTAINER[p.kind](p)}`)
}

/** 组合图：主容器摆在品牌纸盒前 */
function scenePair(p) {
  const b = brandOf(p)
  const box = `
  <g>
    <polygon points="96,236 160,204 344,204 282,236" fill="#ffffff" stroke="#e5dccb" stroke-width="2"/>
    <polygon points="282,236 344,204 344,452 282,486" fill="${b.tint}" stroke="#e0d5c2" stroke-width="2"/>
    <rect x="96" y="236" width="186" height="250" fill="url(#body)" stroke="#e5dccb" stroke-width="2"/>
    <rect x="96" y="236" width="186" height="46" fill="url(#brandfill)"/>
    <text x="189" y="268" text-anchor="middle" font-family="${FONT}" font-size="20" font-weight="600" fill="#ffffff" letter-spacing="2">${esc(b.name)}</text>
    <circle cx="189" cy="360" r="34" fill="none" stroke="${b.c}" stroke-opacity="0.5" stroke-width="5"/>
    <text x="189" y="370" text-anchor="middle" font-family="${FONT}" font-size="28" font-weight="600" fill="${b.deep}">${esc(b.name[0])}</text>
  </g>`
  return svgDoc(`${backdrop(p)}
  ${box}
  ${groundShadow(364, 500, 118)}
  <g transform="translate(118 74) scale(0.82)">${CONTAINER[p.kind](p)}</g>`)
}

/** 内容物平铺图：把「里面装的是什么」摊开来拍 */
function sceneSpread(p) {
  const b = brandOf(p)
  const c = p.contents ? CONTENT[p.contents] : null
  let items
  if (!c) {
    // 袋装/盒装商品：摊开的是分装小袋
    items = `
    <g transform="translate(206 322) rotate(-16)"><rect x="-42" y="-96" width="84" height="192" rx="14" fill="#faf6ee" stroke="#e3d9c6" stroke-width="2"/><rect x="-42" y="-96" width="84" height="40" rx="14" fill="${b.c}"/></g>
    <g transform="translate(394 322) rotate(14)"><rect x="-42" y="-96" width="84" height="192" rx="14" fill="#faf6ee" stroke="#e3d9c6" stroke-width="2"/><rect x="-42" y="-96" width="84" height="40" rx="14" fill="${b.c}"/></g>
    <g transform="translate(300 340) rotate(-2)"><rect x="-46" y="-104" width="92" height="208" rx="16" fill="#ffffff" stroke="#ddd0b8" stroke-width="2"/><rect x="-46" y="-104" width="92" height="44" rx="16" fill="url(#brandfill)"/><text x="0" y="30" text-anchor="middle" font-family="${FONT}" font-size="24" font-weight="600" fill="#2f2418">${esc(p.label[0])}</text></g>`
  } else if (c.kind === 'powder') {
    items = `${drawContent('powder-cream', 300, 330, 0, 2.1)}
    <g transform="translate(430 420) rotate(24)">
      <ellipse cx="0" cy="0" rx="34" ry="21" fill="#f4eee0" stroke="#ddd0b8" stroke-width="2.5"/>
      <path d="M26 -10 L70 -34" stroke="#ddd0b8" stroke-width="9" stroke-linecap="round"/>
    </g>`
  } else {
    const pos = [[188, 236, -24], [300, 216, 10], [412, 240, 22], [238, 352, 6], [362, 356, -8], [300, 452, -14]]
    items = pos.map(([x, y, r], i) => {
      let item = drawContent(p.contents, x, y, r, 1.3 + (i % 2) * 0.12)
      if (c.kind === 'gummy' && i % c.colors.length !== 0) {
        item = item.replace(c.colors[0], c.colors[i % c.colors.length])
      }
      return item
    }).join('')
  }
  return svgDoc(`${backdrop(p)}
  <ellipse cx="300" cy="470" rx="212" ry="32" fill="#4a3a29" opacity="0.07"/>
  ${items}
  <text x="300" y="556" text-anchor="middle" font-family="${FONT}" font-size="20" fill="#7d6b52" letter-spacing="4">${esc(b.name)}</text>`)
}

/** 特写图：一颗放大的内容物 + 光泽（目前只有 SPU001 用，需要软胶囊的内容物渲染） */
function sceneMacro(p) {
  const b = brandOf(p)
  const c = CONTENT[p.contents ?? 'softgel-amber']
  return svgDoc(`${backdrop(p)}
  ${groundShadow(316, 486, 178)}
  ${drawContent(p.contents ?? 'softgel-amber', 150, 470, -30, 0.9)}
  ${drawContent(p.contents ?? 'softgel-amber', 470, 180, 26, 0.8)}
  <g transform="translate(300 300) rotate(-16)">
    <rect x="-92" y="-128" width="184" height="256" rx="92" fill="url(#cg)" stroke="${c.b}" stroke-width="3"/>
    <line x1="-90" y1="-8" x2="90" y2="-8" stroke="#ffffff" stroke-opacity="0.45" stroke-width="4"/>
    <ellipse cx="-34" cy="-66" rx="26" ry="44" fill="#ffffff" opacity="0.55"/>
    <ellipse cx="36" cy="62" rx="18" ry="30" fill="#ffffff" opacity="0.25"/>
  </g>
  <g fill="${b.c}" opacity="0.55">
    <path d="M150 150 l6 16 16 6 -16 6 -6 16 -6 -16 -16 -6 16 -6 z"/>
    <path d="M462 388 l5 12 12 5 -12 5 -5 12 -5 -12 -12 -5 12 -5 z"/>
  </g>`)
}

const SCENE = { hero: sceneHero, pair: scenePair, spread: sceneSpread, macro: sceneMacro }

// ==================== 品牌标与评价图 ====================

function brandLogo(id) {
  const b = BRAND[id]
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120" role="img" aria-label="${esc(b.name)}">
  <defs><linearGradient id="l" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="${b.c}"/><stop offset="1" stop-color="${b.deep}"/>
  </linearGradient></defs>
  <rect width="120" height="120" rx="28" fill="url(#l)"/>
  <rect x="8" y="8" width="104" height="104" rx="22" fill="none" stroke="#ffffff" stroke-opacity="0.28" stroke-width="2"/>
  <text x="60" y="76" text-anchor="middle" font-family="${FONT}" font-size="54" font-weight="600" fill="#ffffff">${esc(b.name[0])}</text>
</svg>`
}

/**
 * 评价图：买家视角的「随手拍」——暖光、桌面、产品略偏不居中的构图，
 * 与商品图的影棚感刻意拉开距离；角落留「买家实拍」字样。
 */
function reviewPhoto(spuId, variant) {
  const p = product(spuId)
  const b = brandOf(p)
  const c = p.contents ? CONTENT[p.contents] : null
  const grad = c && (c.kind === 'softgel' || c.kind === 'capsule')
    ? `<linearGradient id="cg" x1="0" y1="0" x2="0.6" y2="1"><stop offset="0" stop-color="${c.a}"/><stop offset="1" stop-color="${c.b}"/></linearGradient>`
    : ''
  const s = variant === 'b' ? 0.9 : 0.78
  const dx = variant === 'b' ? 34 : -8
  const dy = variant === 'b' ? 40 : 70
  const tilt = variant === 'b' ? -3 : 2
  return svgDoc(`
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0.4" y2="1">
      <stop offset="0" stop-color="#f6efe0"/><stop offset="1" stop-color="#e9ddc6"/>
    </linearGradient>
    <radialGradient id="vin" cx="0.5" cy="0.42" r="0.85">
      <stop offset="0.55" stop-color="#000000" stop-opacity="0"/>
      <stop offset="1" stop-color="#4a3a29" stop-opacity="0.20"/>
    </radialGradient>
    <linearGradient id="body" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#f3ecdf"/></linearGradient>
    <linearGradient id="brandfill" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${b.c}"/><stop offset="1" stop-color="${b.deep}"/></linearGradient>
    ${grad}
  </defs>
  <rect width="600" height="600" fill="url(#bg)"/>
  <rect y="150" width="600" height="450" fill="#efe5d1"/>
  <rect y="148" width="600" height="6" fill="#e0d4bc"/>
  ${groundShadow(300 * s + dx, dy + 508 * s, 150 * s)}
  <g transform="translate(${dx} ${dy}) rotate(${tilt}) scale(${s})">${CONTAINER[p.kind](p)}</g>
  <rect width="600" height="600" fill="url(#vin)"/>
  <text x="556" y="576" text-anchor="end" font-family="${FONT}" font-size="22" fill="#7d6b52" opacity="0.75">买家实拍</text>`)
}

function svgDoc(inner) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 600">${inner}\n</svg>\n`
}

// ==================== 对账与生成 ====================

/** data.sql 里全部 `/img/photos/x-y.svg` 引用 → { seed → size } */
function photoRefs(sqlPath) {
  const refs = new Map()
  for (const m of readFileSync(sqlPath, 'utf8').matchAll(/\/img\/photos\/([a-z0-9]+)-(\d+)\.svg/g)) {
    refs.set(m[1], Number(m[2]))
  }
  return refs
}

/** SKU 种子行 → seed 号 → spu_id（首行带 as 别名，union 行不带） */
function parseSkus() {
  const sql = readFileSync(PRODUCT_SQL, 'utf8')
  const skus = []
  for (const line of sql.split('\n')) {
    const seed = line.match(/\/img\/photos\/sku(\d+)-400\.svg/)
    if (!seed) continue
    const ids = line.match(/select (\d+)(?: as id,|,) (\d+)(?: as spu_id,|,)/)
    if (!ids) throw new Error(`认不出 SKU 行长什么样：${line.trim()}`)
    skus.push({ n: Number(seed[1]), spu: Number(ids[2]) })
  }
  return skus
}

function expectedRefs() {
  const refs = new Map()
  for (const p of PRODUCTS) {
    refs.set(`spu${p.id}`, 600)
    const g = p.gallery ?? ['spread']
    g.forEach((_, i) => refs.set(`spu${p.id}${'abc'[i]}`, 800))
  }
  for (const id of [1, 2, 3, 4, 5, 6]) refs.set(`brand${id}`, 120)
  for (const s of parseSkus()) refs.set(`sku${s.n}`, 400)
  return refs
}

// 评价图清单：seed → 商品。前 8 条是 review-service/data.sql 的负数种子评价；
// rev1 不在种子之列——它是早期通过接口创建的真实评价（验收残留），一并本地化
const REVIEWS = [
  { seed: 'rev1', spu: 1 },
  { seed: 'rev1a', spu: 1 }, { seed: 'rev1b', spu: 1 }, { seed: 'rev4a', spu: 1 },
  { seed: 'rev13a', spu: 3 }, { seed: 'rev13b', spu: 3 },
  { seed: 'rev18a', spu: 5 },
  { seed: 'rev30a', spu: 12 }, { seed: 'rev30b', spu: 12 },
]

function reconcile() {
  const got = new Map([...photoRefs(PRODUCT_SQL), ...photoRefs(REVIEW_SQL)])
  const want = expectedRefs()
  for (const r of REVIEWS) want.set(r.seed, 600)
  const missing = [...got.keys()].filter((k) => !want.has(k) || want.get(k) !== got.get(k))
  const extra = [...want.keys()].filter((k) => !got.has(k) || got.get(k) !== want.get(k))
  if (missing.length || extra.length) {
    console.error('data.sql 的配图引用与清单对不上，先对齐再生成：')
    if (missing.length) console.error('  data.sql 有、清单没有：', missing.join(', '))
    if (extra.length) console.error('  清单有、data.sql 没有：', extra.join(', '))
    process.exit(1)
  }
}

function main() {
  reconcile()
  rmSync(OUT, { recursive: true, force: true })
  mkdirSync(join(OUT, 'photos'), { recursive: true })

  let n = 0
  const write = (name, content) => {
    writeFileSync(join(OUT, 'photos', name), content)
    n++
  }

  for (const p of PRODUCTS) {
    write(`spu${p.id}-600.svg`, sceneHero(p))
    const g = p.gallery ?? ['spread']
    g.forEach((sceneName, i) => write(`spu${p.id}${'abc'[i]}-800.svg`, SCENE[sceneName](p)))
  }
  for (const id of [1, 2, 3, 4, 5, 6]) write(`brand${id}-120.svg`, brandLogo(id))
  for (const s of parseSkus()) {
    // SKU 与其 SPU 共用商品主图：规格差异体现在选项文字上，照片是同一条商品
    write(`sku${s.n}-400.svg`, sceneHero(product(s.spu)))
  }
  for (const r of REVIEWS) write(`${r.seed}-600.svg`, reviewPhoto(r.spu, r.seed.endsWith('b') ? 'b' : 'a'))

  console.log(`已生成 ${n} 张 → ${OUT}\\photos`)
}

main()
