#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""StellarCraft: 剑星敌人血量 -> MC 伤害 换算表生成器

换算模型（照搬 SekiroCraft-Passthrough 的 budget 归一化）:
    fraction = MC_damage / budget(tier)
    原生HP -= maxHP * fraction
  => 击杀所需 MC 伤害 = budget(tier)
  => 等效汇率 k = maxHP / budget   (1 点 MC 伤害 扣掉多少原生 HP)

数据源: gamerguides Stellar Blade Database (2026-10-08 抓取)。
HP 取「常规难度」数值（页面给出两档时取较低值）。

用法:
    python hp_conversion.py            # 打印控制台表格
    python hp_conversion.py --html     # 额外生成 HP_CONVERSION.html
"""

import html
import os
import sys

# tier, name, hp_normal, shield, shield_count, internal
DATA = [
    # ---- Naytiba Minion (11) ----
    ("Minion", "Thornhead",            1200,   80, 1, "ch_m_na_52"),
    ("Minion", "Creeper",               384,   24, 1, "ch_m_na_11"),
    ("Minion", "Test Subject",         1550,  182, 1, "ch_m_na_41"),
    ("Minion", "Boarlet",              2000,   84, 1, "ch_m_na_12_3"),
    ("Minion", "Mite",                 2250,   88, 1, "ch_m_na_12_1"),
    ("Minion", "Mutated Mite",         2250,   88, 1, "ch_m_na_12_2"),
    ("Minion", "Skulling",             2800,   88, 1, "ch_m_na_14"),
    ("Minion", "Lesser Lurker",        3200,   90, 1, "ch_m_na_47"),
    ("Minion", "Aid Drone",            4400,   96, 1, "ch_m_na_32"),
    ("Minion", "Broken Droid",         4400,   96, 1, "ch_m_na_29_var3"),
    ("Minion", "Tentacle",             4400,   96, 1, "ch_m_na_28"),

    # ---- Naytiba Warrior ----
    ("Warrior", "Guard Turret",         1860,   91, 1, "ch_m_na_27"),
    ("Warrior", "Cricket Boomer",       3360,  205, 2, "ch_m_na_03"),
    ("Warrior", "Cannon Guardian",      3360,  205, 2, "ch_m_na_40_type_c"),
    ("Warrior", "Lurker",               3525,  116, 2, "ch_m_na_45"),
    ("Warrior", "Hive",                 3532,  180, 4, "ch_m_na_20_a"),
    ("Warrior", "Beholder",             4200,  240, 3, "ch_m_na_51"),
    ("Warrior", "Infector",             4390,  210, 2, "ch_m_na_35"),
    ("Warrior", "Hydra",                4590,  333, 3, "ch_m_na_09"),
    ("Warrior", "Mutated Boarlet",      4900,  210, 2, "ch_m_na_12_4"),
    ("Warrior", "Skull Gunner",         5040,  220, 2, "ch_m_na_19"),
    ("Warrior", "Heavy Guardian",       6190,  456, 4, "ch_m_na_40_type_b"),
    ("Warrior", "Cricket Slasher",      6230,  340, 3, "ch_m_na_03_var01"),
    ("Warrior", "Guardian",             6500,  339, 3, "ch_m_na_40"),
    ("Warrior", "Mutated Creeper",      6500,  340, 3, "ch_m_na_11_typeb"),
    ("Warrior", "Turret Droid",         6800,  380, 3, "ch_m_na_48"),
    ("Warrior", "Mutated Hydra",        7800,  388, 4, "ch_m_na_09_typeb"),
    ("Warrior", "Hedgeboar",            8190,  237, 3, "ch_m_na_44_b"),
    ("Warrior", "Waster",               8500,  380, 3, "ch_m_na_34"),
    ("Warrior", "Lump",                 8500,  380, 3, "ch_m_na_30"),
    ("Warrior", "Barnacle",             8510,  303, 3, "ch_m_na_08"),
    ("Warrior", "Defiler",              8260,  285, 3, "ch_m_na_37"),
    ("Warrior", "Cricket Butcher",      9200,  423, 3, "ch_m_na_03_var02"),
    ("Warrior", "Skull Fighter",       10000,  417, 3, "ch_m_na_18"),
    ("Warrior", "Sentryborg",          10920,  336, 2, "ch_m_na_24"),
    ("Warrior", "Dozer",               11500,  460, 4, "ch_m_na_07_02_a"),
    ("Warrior", "Mutated Defiler",     11860,  320, 4, "ch_m_na_37_type_b"),
    ("Warrior", "Skull Trooper",       12500,  460, 3, "ch_m_na_17"),
    ("Warrior", "Droid",               13650,  336, 2, "ch_m_na_29_var1"),
    ("Warrior", "Houndborg",           13650,  336, 2, "ch_m_na_23"),
    ("Warrior", "Old Droid",           15000,  520, 4, "ch_m_na_49"),
    ("Warrior", "Skull Berserker",     17050,  640, 4, "ch_m_na_16"),
    ("Warrior", "Battle Droid",        17280,  428, 4, "ch_m_na_29"),
    ("Warrior", "Lesser Behemoth",     18940,  840, 4, "ch_m_na_46"),
    ("Warrior", "Cocoon",              33300,  696, 3, "ch_m_na_57"),
    ("Warrior", "Machine Hive",        50400, 1232, 4, "ch_m_na_20_var2"),
    ("Warrior", "Heavy Droid",         53812,  868, 3, "ch_m_na_29_var2"),

    # ---- Elite Naytiba ----
    ("Elite",   "Brute",               30000, 2000, 5, "ch_m_na_43"),
    ("Elite",   "Behemoth",            37840, 1455, 5, "ch_m_na_46_var01"),
    ("Elite",   "Abaddon",             44000, 2160, 5, "ch_m_na_39"),
    ("Elite",   "Corrupter",           49500, 2430, 5, "ch_m_na_07"),
    ("Elite",   "Belial",              73600, 3280, 5, "ch_m_na_31"),
    ("Elite",   "Maelstrom",          100000, 3100, 5, "ch_m_na_36"),
    ("Elite",   "Stalker",            104000, 2640, 5, "ch_m_na_13"),
    ("Elite",   "Juggernaut",         117000, 2970, 5, "ch_m_na_15"),
    ("Elite",   "Karakuri",           248400, 3690, 5, "ch_m_na_26"),
    ("Elite",   "Democrawler",        276000, 4100, 5, "ch_m_na_22"),

    # ---- Alpha Naytiba ----
    ("Alpha",   "Gigas",               55000,  2700, 5, "ch_m_na_05"),
    ("Alpha",   "Demogorgon",         100000, 10000, 1, "ch_m_na_33"),
    ("Alpha",   "Tachy",              130000,  3300, 5, "ch_m_na_21"),
    ("Alpha",   "Unidentified Naytiba",306000, 4300, 5, "ch_m_na_42"),
    ("Alpha",   "Raven",              336000,  4500, 5, "ch_npc_raven"),

    # ---- Elder Naytiba ----
    ("Elder",   "Adam（Elder 第一形态）",  91500, 1075, 5, "ch_m_na_55"),
    ("Elder",   "Elder Naytiba（真身）",  366000, 4700, 5, "ch_m_na_56"),
]

# 档位预算：击杀所需的 MC 伤害总量。
# 以「只狼血条」为单位，1 条 = 40 点 MC 伤害（只狼: 普通敌人 40 = 1 条, Boss 每阶段 200 = 5 条）。
BUDGET = {
    "Minion":  20,    # 0.5 条
    "Warrior": 40,    # 1 条   <- 与只狼普通敌人完全一致
    "Elite":  100,    # 2.5 条
    "Alpha":  200,    # 5 条   <- 与只狼 Boss 每阶段完全一致
    "Elder":  200,    # 5 条 / 形态
}
TIERS = ["Minion", "Warrior", "Elite", "Alpha", "Elder"]
TIER_CN = {"Minion": "杂兵", "Warrior": "战士", "Elite": "精英", "Alpha": "首领", "Elder": "最终Boss"}
TIER_EN = {"Minion": "Naytiba Minion", "Warrior": "Naytiba Warrior", "Elite": "Elite Naytiba",
           "Alpha": "Alpha Naytiba", "Elder": "Elder Naytiba"}
TIER_NOTE = {
    "Minion":  "杂鱼，一刀两刀清场",
    "Warrior": "标准敌人 —— 与只狼普通敌人同价",
    "Elite":   "需要认真打，介于杂兵与 Boss 之间",
    "Alpha":   "大 Boss，每阶段 —— 与只狼 Boss 每阶段同价",
    "Elder":   "最终 Boss，按形态各结算一次",
}
WEAPONS = [("木剑", 4), ("石剑", 5), ("铁剑", 6), ("钻石剑", 7), ("下界合金剑", 8)]


def med(xs):
    xs = sorted(xs)
    return xs[len(xs) // 2]


# --------------------------------------------------------------------------- HTML
CSS = """
*{box-sizing:border-box}
body{margin:0;padding:40px 24px 80px;background:#f7fafd;color:#10243a;
 font:15px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
.wrap{max-width:1120px;margin:0 auto}
h1{font-size:27px;font-weight:600;margin:0 0 6px;letter-spacing:-.2px}
h2{font-size:19px;font-weight:600;margin:44px 0 14px;padding-bottom:9px;
 border-bottom:2px solid #cfe3f7}
h3{font-size:15px;font-weight:600;margin:26px 0 10px;color:#185fa5}
.sub{color:#5a7690;font-size:14px;margin:0 0 28px}
.card{background:#fff;border:1px solid #d9e6f4;border-radius:12px;padding:20px 22px;margin:16px 0}
.card.accent{border-color:#a9cdf0;background:#f2f8ff}
.card.warn{border-color:#f0c9a9;background:#fff8f2}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(215px,1fr));gap:14px;margin:18px 0}
.kpi{background:#fff;border:1px solid #d9e6f4;border-radius:12px;padding:16px 18px}
.kpi .t{font-size:12px;color:#5a7690;letter-spacing:.3px}
.kpi .v{font-size:25px;font-weight:600;color:#185fa5;margin:5px 0 2px;font-variant-numeric:tabular-nums}
.kpi .n{font-size:12px;color:#7b93a8}
table{width:100%;border-collapse:collapse;font-size:13.5px;background:#fff;
 border:1px solid #d9e6f4;border-radius:10px;overflow:hidden;margin:12px 0}
th{background:#eaf3fc;color:#14456f;font-weight:600;text-align:left;
 padding:10px 12px;border-bottom:1px solid #cfe3f7;white-space:nowrap}
td{padding:8px 12px;border-bottom:1px solid #edf3f9;font-variant-numeric:tabular-nums}
tr:last-child td{border-bottom:none}
tr:hover td{background:#f6faff}
.num{text-align:right;white-space:nowrap}
.mono{font-family:ui-monospace,Consolas,monospace;font-size:12.5px;color:#4a6a86}
.pill{display:inline-block;padding:1px 9px;border-radius:20px;font-size:12px;font-weight:600}
.p-m{background:#e6f1fb;color:#185fa5}.p-w{background:#e1f5ee;color:#0f6e56}
.p-e{background:#faeeda;color:#854f0b}.p-a{background:#fcebeb;color:#a32d2d}
.p-x{background:#eeedfe;color:#534ab7}
tr.grp td{background:#f2f8ff;font-weight:600;color:#14456f;border-top:1px solid #cfe3f7}
code{background:#eef4fa;padding:1px 6px;border-radius:4px;
 font-family:ui-monospace,Consolas,monospace;font-size:13px;color:#14456f}
pre{background:#10243a;color:#d6e6f5;padding:16px 18px;border-radius:10px;overflow-x:auto;
 font-family:ui-monospace,Consolas,monospace;font-size:13px;line-height:1.6;margin:12px 0}
pre .c{color:#7fa8c9}pre .k{color:#8fd3a8}pre .n{color:#f0c987}
ul{margin:8px 0;padding-left:22px}li{margin:5px 0}
.foot{margin-top:48px;padding-top:18px;border-top:1px solid #d9e6f4;color:#7b93a8;font-size:12.5px}
"""


def pill(tier):
    cls = {"Minion": "p-m", "Warrior": "p-w", "Elite": "p-e", "Alpha": "p-a", "Elder": "p-x"}[tier]
    return f'<span class="pill {cls}">{TIER_CN[tier]}</span>'


def write_html(path):
    P = []
    A = P.append

    A('<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">')
    A('<meta name="viewport" content="width=device-width,initial-scale=1">')
    A('<title>StellarCraft — 剑星血量 → MC 伤害换算表</title>')
    A(f'<style>{CSS}</style></head><body><div class="wrap">')

    A('<h1>剑星血量 → MC 伤害 换算表</h1>')
    A('<p class="sub">参照 SekiroCraft-Passthrough（只狼方案）的 budget 归一化模型　·　'
      '数据源：gamerguides Stellar Blade Database　·　2026-10-08</p>')

    allhp = [d[2] for d in DATA]
    A('<div class="grid">')
    for t, v, n in [
        ("收录敌人", f"{len(DATA)}", "5 个档位，含全部 Boss"),
        ("血量跨度", f"{max(allhp)//min(allhp)} 倍", f"{min(allhp):,} → {max(allhp):,}"),
        ("普通战士档", "40", "= 只狼普通敌人（1 条血）"),
        ("Boss 档", "200 / 形态", "= 只狼 Boss 每阶段（5 条血）"),
    ]:
        A(f'<div class="kpi"><div class="t">{t}</div><div class="v">{v}</div><div class="n">{n}</div></div>')
    A('</div>')

    # ---- 1. 模型 ----
    A('<h2>1　换算是怎么算的</h2>')
    A('<p>只狼方案的原始实现（<code>native_combat.hpp:29</code> 与 <code>:157</code>）只有三行，'
      '核心是<b>不看原生血量，只看档位</b>：</p>')
    A('<pre><span class="c">// 档位预算：普通敌人 40 点 MC 伤害，Boss 200 点</span>\n'
      '<span class="k">int</span> healthPoints(<span class="k">const</span> Vital &amp;v) <span class="k">const</span>'
      ' { <span class="k">return</span> v.bossNode &gt; 0 ? bossHealthPoints_ : normalHealthPoints_; }\n\n'
      '<span class="c">// 把「MC 伤害」换成「扣掉百分之几的血条」，再乘回去</span>\n'
      '<span class="k">auto</span> target = damageHp(v.hp, v.maxHp, cmd.amount / healthPoints(v), 0);</pre>')
    A('<div class="card accent"><b>结论：</b>只狼定义的<b>不是汇率，是预算</b>。'
      '40 点 MC 伤害 = 打空一条普通敌人血条，200 点 = 打空一个 Boss 阶段血条；'
      '原生血量是多少完全不参与运算。<br><br>本文档把同一套模型套到剑星上：'
      '<b>按剑星自己的 5 个敌人档位各给一个击杀预算</b>，再用真实血量反推出等效汇率 '
      '<code>k = 原生HP ÷ 预算</code>，供 Stellar Blade 侧扣血时使用。</div>')

    # ---- 2. 档位预算 ----
    A('<h2>2　档位预算表</h2>')
    A('<p>剑星官方就把敌人分成 5 类，正好直接用作档位。以「只狼血条 = 40 点 MC 伤害」为单位：</p>')
    A('<table><tr><th>档位</th><th>英文</th><th class="num">击杀预算<br>（MC 伤害）</th>'
      '<th class="num">折合只狼血条</th>' + ''.join(f'<th class="num">{w}<br>({d})</th>' for w, d in WEAPONS)
      + '<th>定位</th></tr>')
    for t in TIERS:
        b = BUDGET[t]
        A(f'<tr><td>{pill(t)}</td><td>{TIER_EN[t]}</td><td class="num"><b>{b}</b></td>'
          f'<td class="num">{b/40:.2f} 条</td>'
          + ''.join(f'<td class="num">{b/d:.1f} 刀</td>' for _, d in WEAPONS)
          + f'<td>{TIER_NOTE[t]}</td></tr>')
    A('</table>')
    A('<p class="mono">铁剑 = 6 点基础伤害；暴击 ×1.5；锋利 V 约 +3 点。'
      '实际手感按玩家配装上下浮动。</p>')

    # ---- 3. 逐敌人 ----
    A('<h2>3　逐敌人换算结果</h2>')
    A('<p><code>k</code> = 1 点 MC 伤害应扣掉多少原生 HP。'
      'Stellar Blade 侧结算时：<code>扣血 = MC伤害 × k</code>。'
      '同一敌人在「只狼条数」列上都是同一个值（这就是归一化），差异全部由 k 吸收。</p>')
    A('<table><tr><th>敌人</th><th class="num">原生 HP</th><th class="num">护盾</th>'
      '<th class="num">盾数</th><th class="num">击杀所需<br>MC 伤害</th>'
      '<th class="num">汇率 k<br>(HP/点)</th><th class="num">钻石剑<br>刀数</th>'
      '<th>内部名</th></tr>')
    for t in TIERS:
        b = BUDGET[t]
        A(f'<tr class="grp"><td colspan="8">{pill(t)}　{TIER_EN[t]}　—　预算 {b} 点 MC 伤害'
          f'（k 区间 {min(d[2] for d in DATA if d[0]==t)/b:,.0f} ~ '
          f'{max(d[2] for d in DATA if d[0]==t)/b:,.0f}）</td></tr>')
        for tt, name, hp, sh, sc, internal in DATA:
            if tt != t:
                continue
            k = hp / b
            A(f'<tr><td>{html.escape(name)}</td><td class="num">{hp:,}</td>'
              f'<td class="num">{sh:,}</td><td class="num">{sc}</td>'
              f'<td class="num"><b>{b}</b></td><td class="num">{k:,.1f}</td>'
              f'<td class="num">{b/7:.1f}</td><td class="mono">{internal}</td></tr>')
    A('</table>')
    A('<div class="card"><b>护盾怎么处理：</b>剑星的护盾（Shield）是独立于血量的第二条槽，'
      '地位相当于只狼的<b>姿态</b>——只狼那边也是单独给姿态配一个比例。'
      '护盾按<b>实时 MaxShield / 档位预算</b>换算，HP 仍按实时 MaxHP / 档位预算换算。'
      '有盾的本击只扣盾，破盾不溢出扣血，下一击才进入 HP 结算；不另设可调倍率。</div>')

    # ---- 4. 反证 ----
    A('<h2>4　为什么不能沿用「单一汇率」</h2>')
    A('<p>如果把只狼的 40/200 理解成一个固定汇率，锚定「战士档 = 40 点」，'
      f'得到全局 k ≈ {med([d[2] for d in DATA if d[0]=="Warrior"])/40:,.1f}，那么：</p>')
    K = med([d[2] for d in DATA if d[0] == "Warrior"]) / 40
    A('<table><tr><th>档位</th><th class="num">HP 中位数</th>'
      '<th class="num">需要 MC 伤害</th><th class="num">钻石剑刀数</th><th>评价</th></tr>')
    for t in TIERS:
        m = med([d[2] for d in DATA if d[0] == t])
        h = m / K
        verdict = "合理" if h < 60 else ("偏多" if h < 200 else "不可玩")
        A(f'<tr><td>{pill(t)}</td><td class="num">{m:,}</td><td class="num">{h:,.1f}</td>'
          f'<td class="num">{h/7:,.1f}</td><td>{verdict}</td></tr>')
    A('</table>')
    A('<div class="card warn"><b>所以必须分档。</b>剑星血量跨度约 950 倍'
      '（Creeper 384 → Elder Naytiba 366,000），只狼的血量跨度要小得多，'
      '所以它两档就够用。这也是本次换算唯一偏离只狼的地方——'
      '<b>档位从 2 个扩到 5 个，但每个档位的预算定义方式与只狼完全一致</b>：'
      '普通档 40、Boss 档 200 两个数字被原样保留。</div>')

    # ---- 5. 落地 ----
    A('<h2>5　落地到 StellarCraft</h2>')
    A('<p>改动很小，因为现有链路已经够用——只需要把「固定倍率」换成「按档位查表」。</p>')
    A('<table><tr><th>位置</th><th>现在</th><th>改成</th></tr>')
    for a, b_, c in [
        ("<code>sb-mod/lua/main.lua</code>", "按类名前缀 <code>CH_M_NA</code> 判断是不是敌人",
         "再取 <code>actor.MaxHP</code> 判档，或维护一张 <code>内部名 → 档位</code> 表"),
        ("<code>damage_actor()</code>", "直接从 <code>Health</code> 减绝对值",
         "改成 <code>Health -= MaxHP × (MC伤害 ÷ 预算)</code>"),
        ("<code>DamageBridge.MC_TO_SB_SCALE = 5.0f</code>", "固定 ×5",
         "删除；协议改成传<b>原始 MC 伤害</b>，档位换算放到 SB 侧做"),
        ("<code>ExplosionMixin.SCALE = 10.0f</code>", "爆炸固定 ×10",
         "保留，但改为「按档位归一化后再乘爆炸权重」"),
    ]:
        A(f'<tr><td>{a}</td><td>{b_}</td><td>{c}</td></tr>')
    A('</table>')
    A('<div class="card accent">档位判定建议放在 Stellar Blade 侧，因为那边能直接读 '
      '<code>MaxHP</code> 和内部类名，判档准确率最高；MC 侧只管把「我打了多少点 MC 伤害」'
      '原样发过去即可。这样以后调平衡只改 <code>main.lua</code> 里的一张表，不用重编 jar。</div>')

    A('<div class="foot">数据来源：gamerguides.com / earth.gamerguides.com '
      'Stellar Blade Database（2026-10-08 抓取）。HP 取常规难度值；页面同时给出两档数值时取较低值。'
      '只狼侧语义依据：SekiroCraft-Passthrough <code>src/native_combat.hpp</code>、'
      '<code>config/sekirobridge.ini</code>。生成脚本：<code>stellarcraft/tools/hp_conversion.py</code>。</div>')

    A('</div></body></html>')

    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(P))
    return len("".join(P))


# --------------------------------------------------------------------------- CLI
def main():
    print("=" * 104)
    print("档位预算表")
    print("=" * 104)
    print(f"{'档位':<12}{'预算':>8}{'只狼条数':>10}  " + "  ".join(f"{w}({d})" for w, d in WEAPONS))
    for t in TIERS:
        b = BUDGET[t]
        print(f"{TIER_CN[t]:<10}{b:>8}{b/40:>10.2f}  "
              + "  ".join(f"{b/d:>7.1f}刀" for _, d in WEAPONS))

    print()
    print("=" * 104)
    print("逐敌人换算表   k = 原生HP / 预算")
    print("=" * 104)
    print(f"{'档位':<8}{'敌人':<30}{'HP':>9}{'护盾':>8}{'盾数':>5}{'预算':>6}{'汇率k':>10}   内部名")
    for t in TIERS:
        for tt, name, hp, sh, sc, internal in DATA:
            if tt != t:
                continue
            b = BUDGET[t]
            print(f"{TIER_CN[t]:<6}{name:<32}{hp:>9,}{sh:>8,}{sc:>5}{b:>6}{hp/b:>10,.1f}   {internal}")
        print("-" * 104)

    print()
    print("=" * 104)
    print("反证：若坚持单一全局汇率（锚定战士档 = 40 点）")
    print("=" * 104)
    K = med([d[2] for d in DATA if d[0] == "Warrior"]) / 40
    print(f"全局 k = {K:,.1f}")
    for t in TIERS:
        m = med([d[2] for d in DATA if d[0] == t])
        print(f"  {TIER_CN[t]:<10} HP 中位 {m:>9,}  ->  {m/K:>9,.1f} MC 伤害"
              f" = 钻石剑 {m/K/7:>7.1f} 刀")
    allhp = sorted(d[2] for d in DATA)
    print(f"\n血量跨度: {allhp[0]:,} ~ {allhp[-1]:,} = {allhp[-1]/allhp[0]:,.0f} 倍")

    if "--html" in sys.argv:
        out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "docs", "HP_CONVERSION.html")
        out = os.path.normpath(out)
        n = write_html(out)
        print(f"\n[OK] 已生成 {out}  ({n:,} bytes)")


if __name__ == "__main__":
    main()
