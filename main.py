"""网格交易收益计算器 - 安卓版(Kivy)
与 Windows 版完全相同的计算逻辑:等比/等差网格,路径法爆仓价。
"""
import math
import os

from kivy.app import App
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.spinner import Spinner, SpinnerOption
from kivy.uix.widget import Widget
from kivy.core.window import Window
from kivy.graphics import Color, Line, Rectangle, RoundedRectangle

# 安卓系统自带中文字体,按优先级选用,找不到则回退默认字体
FONT = None
for _p in ("/system/fonts/NotoSansCJK-Regular.ttc",
           "/system/fonts/NotoSansSC-Regular.otf",
           "/system/fonts/DroidSansFallback.ttf"):
    if os.path.exists(_p):
        FONT = _p
        break


# ---- 主题:深色金融(dark-saas) + 浅色,跟随系统/手动三档 ----
THEMES = {
    "dark": dict(
        BG=(0.004, 0.004, 0.008, 1),      # 画布近黑,绝不用纯黑
        CARD=(0.059, 0.063, 0.067, 1),    # 卡片 surface-1
        SURF2=(0.078, 0.082, 0.086, 1),   # 输入框/斑马纹 surface-2
        INK=(0.969, 0.973, 0.973, 1),
        SUB=(0.816, 0.839, 0.878, 1),
        FAINT=(0.541, 0.561, 0.596, 1),
        BLUE=(0.369, 0.416, 0.824, 1),    # 唯一强调色
        BLUE_DARK=(0.298, 0.337, 0.671, 1),
        TEAL=(0.153, 0.651, 0.267, 1),
        RED=(0.898, 0.282, 0.302, 1),
        BORDER=(0.137, 0.145, 0.165, 1),  # hairline
        TEAL_BG=(0.153, 0.651, 0.267, 0.12),
        BLUE_BG=(0.369, 0.416, 0.824, 0.14),
    ),
    "light": dict(
        BG=(0.949, 0.957, 0.973, 1),
        CARD=(1, 1, 1, 1),
        SURF2=(0.937, 0.949, 0.969, 1),
        INK=(0.102, 0.106, 0.137, 1),
        SUB=(0.427, 0.451, 0.510, 1),
        FAINT=(0.604, 0.635, 0.678, 1),
        BLUE=(0.086, 0.365, 1.0, 1),
        BLUE_DARK=(0.066, 0.283, 0.82, 1),
        TEAL=(0.039, 0.635, 0.510, 1),
        RED=(0.851, 0.176, 0.125, 1),
        BORDER=(0.835, 0.859, 0.898, 1),
        TEAL_BG=(0.039, 0.635, 0.510, 0.12),
        BLUE_BG=(0.086, 0.365, 1.0, 0.12),
    ),
}


def apply_theme(name):
    for _k, _v in THEMES[name].items():
        globals()[_k] = _v


apply_theme("dark")


def _system_dark():
    """跟随系统:True 深色 / False 浅色 / None 未知(按深色)."""
    try:
        from jnius import autoclass
        _act = autoclass("org.kivy.android.PythonActivity").mActivity
        _um = _act.getSystemService("uimode")
        if _um.getNightMode() == 2:
            return True
        _cfg = _act.getResources().getConfiguration()
        return (_cfg.uiMode & 0x30) == 0x20
    except Exception:
        return None

# 数字等宽字体(Kivy 自带,桌面/安卓通用),中文仍走系统字体
MONO = None
try:
    from kivy.resources import resource_find as _rf
    _m = _rf("data/fonts/RobotoMono-Regular.ttf")
    if _m and os.path.exists(_m):
        MONO = _m
except Exception:
    MONO = None


def _font(k):
    if FONT:
        k.setdefault("font_name", FONT)
    return k


def _mono(k):
    if MONO:
        try:
            k["font_name"] = MONO
        except TypeError:
            k.font_name = MONO
    return k


class _RoundBg(object):
    """炭黑卡片底 + hairline 描边,跟随 pos/size."""

    def _make_bg(self, radius=dp(10)):
        # 注意:RoundedRectangle.radius 读回的是 [(x,y)]*4 元组,不可回用;
        # 半径自己存一份给描边用
        self._radius = radius
        with self.canvas.before:
            Color(*CARD)
            self._rect = RoundedRectangle(pos=self.pos, size=self.size,
                                          radius=[radius])
            Color(*BORDER)
            self._bd = Line(rounded_rectangle=[self.x, self.y, self.width,
                                               self.height, radius], width=1)
        self.bind(pos=self._upd_bg, size=self._upd_bg)

    def _upd_bg(self, *a):
        r = self._radius
        self._rect.pos = self.pos
        self._rect.size = self.size
        self._bd.rounded_rectangle = [self.x, self.y, self.width,
                                      self.height, r]


class Card(BoxLayout, _RoundBg):
    def __init__(self, **k):
        k.setdefault("orientation", "vertical")
        k.setdefault("padding", dp(14))
        k.setdefault("spacing", dp(10))
        k.setdefault("size_hint_y", None)
        super().__init__(**k)
        self._make_bg()
        self.bind(minimum_height=self.setter("height"))


class L(Label):
    def __init__(self, **k):
        _font(k)
        k.setdefault("font_size", dp(15))
        k.setdefault("color", INK)
        k.setdefault("halign", "left")
        k.setdefault("valign", "middle")
        super().__init__(**k)
        self.bind(size=self._wrap)

    def _wrap(self, *a):
        self.text_size = (self.width, None)


class H(L):
    """小节标题."""

    def __init__(self, **k):
        k.setdefault("font_size", dp(16))
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(30))
        super().__init__(**k)


class D(L):
    def __init__(self, **k):
        k["size_hint_y"] = None
        super().__init__(**k)
        self.bind(texture_size=self._auto_h)

    def _auto_h(self, *a):
        try:
            self.height = self.texture_size[1]
        except Exception:
            pass


class T(TextInput):
    def __init__(self, **k):
        _font(k)
        _mono(k)  # 输入框只进数字,等宽更齐
        k.setdefault("font_size", dp(16))
        k.setdefault("multiline", False)
        k.setdefault("input_filter", "float")
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(48))
        k.setdefault("background_normal", "")
        k.setdefault("background_active", "")
        k.setdefault("background_color", SURF2)
        k.setdefault("foreground_color", INK)
        k.setdefault("cursor_color", BLUE)
        k.setdefault("padding", [dp(10), dp(12), dp(10), dp(12)])
        super().__init__(**k)
        with self.canvas.after:
            self._bc = Color(*BORDER)
            self._bd = Line(rounded_rectangle=[self.x, self.y, self.width,
                                               self.height, dp(8)], width=1.2)
        self.bind(pos=self._upd_bd, size=self._upd_bd, focus=self._upd_bd)

    def _upd_bd(self, *a):
        self._bd.rounded_rectangle = [self.x, self.y, self.width,
                                      self.height, dp(8)]
        if getattr(self, "_bc", None) is not None:
            self._bc.rgba = ((BLUE[0], BLUE[1], BLUE[2], 0.55)
                             if self.focus else BORDER)


class B(Button):
    def __init__(self, ghost=False, **k):
        _font(k)
        k.setdefault("font_size", dp(17))
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(54))
        k.setdefault("background_normal", "")
        k.setdefault("background_down", "")
        k.setdefault("background_color", (0, 0, 0, 0))
        if ghost:
            k.setdefault("color", INK)
        else:
            k.setdefault("color", (1, 1, 1, 1))
        super().__init__(**k)
        self._ghost = ghost
        with self.canvas.before:
            if ghost:
                self._c = None
                Color(*BORDER)
            else:
                self._c = Color(*BLUE)
            self._rect = RoundedRectangle(pos=self.pos, size=self.size,
                                          radius=[dp(8)])
            if ghost:
                self._bl = Line(rounded_rectangle=[self.x, self.y,
                                                   self.width, self.height,
                                                   dp(8)], width=1)
        self.bind(pos=self._upd, size=self._upd, state=self._upd)

    def _upd(self, *a):
        self._rect.pos = self.pos
        self._rect.size = self.size
        if self._ghost:
            self._bl.rounded_rectangle = [self.x, self.y, self.width,
                                          self.height, dp(8)]
        else:
            c = BLUE_DARK if self.state == "down" else BLUE
            self._c.rgb = c[:3]


class SO(SpinnerOption):
    def __init__(self, **k):
        _font(k)
        k.setdefault("background_normal", "")
        k.setdefault("background_color", SURF2)
        k.setdefault("color", INK)
        super().__init__(**k)


class S(Spinner):
    def __init__(self, **k):
        _font(k)
        k.setdefault("option_cls", SO)
        k.setdefault("font_size", dp(16))
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(48))
        k.setdefault("background_normal", "")
        k.setdefault("background_color", SURF2)
        k.setdefault("color", INK)
        super().__init__(**k)
        with self.canvas.after:
            Color(*BORDER)
            self._bd = Line(rounded_rectangle=[self.x, self.y, self.width,
                                               self.height, dp(8)], width=1.2)
        self.bind(pos=self._upd_bd, size=self._upd_bd)

    def _upd_bd(self, *a):
        self._bd.rounded_rectangle = [self.x, self.y, self.width,
                                      self.height, dp(8)]


class Pill(Label):
    """语义徽章:透明底 + 语义色文字(不用实心色块)."""

    def __init__(self, text="", bg=BLUE_BG, fg=BLUE, radius=dp(13), **k):
        _font(k)
        k.setdefault("font_size", dp(12))
        k.setdefault("color", fg)
        k.setdefault("halign", "center")
        k.setdefault("valign", "middle")
        k.setdefault("size_hint", (None, None))
        k.setdefault("size", (dp(52), dp(26)))
        super().__init__(**k)
        self.bind(size=self._wrap)
        with self.canvas.before:
            Color(*bg)
            self._rect = RoundedRectangle(pos=self.pos, size=self.size,
                                          radius=[radius])
        self.bind(pos=self._upd, size=self._upd)

    def _wrap(self, *a):
        self.text_size = (self.width, None)

    def _upd(self, *a):
        self._rect.pos = self.pos
        self._rect.size = self.size


class Row(BoxLayout):
    """明细一行:# / 价格 / 方向药丸 / 累计,斑马纹."""

    def __init__(self, idx, price, pill_text, pill_bg, pill_fg, cum,
                 cum_color, zebra=False, header=False, **k):
        k.setdefault("orientation", "horizontal")
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(30) if header else dp(38))
        k.setdefault("spacing", dp(6))
        k.setdefault("padding", [dp(4), 0])
        super().__init__(**k)
        bg = SURF2 if zebra else CARD
        with self.canvas.before:
            Color(*bg)
            self._rect = Rectangle(pos=self.pos, size=self.size)
        self.bind(pos=self._upd, size=self._upd)
        tc = FAINT if header else INK
        fs = dp(11) if header else dp(12)
        self.add_widget(_mono(L(text=idx, font_size=fs, color=tc,
                                halign="center", size_hint=(None, 1),
                                width=dp(30))))
        self.add_widget(_mono(L(text=price,
                                font_size=dp(15) if not header else fs,
                                color=tc, size_hint_x=1)))
        if pill_bg is None:
            self.add_widget(L(text=pill_text, font_size=fs, color=tc,
                              halign="center", size_hint=(None, 1),
                              width=dp(52)))
        else:
            box = BoxLayout(size_hint=(None, 1), width=dp(52))
            box.add_widget(Widget())
            box.add_widget(Pill(text=pill_text, bg=pill_bg, fg=pill_fg))
            box.add_widget(Widget())
            self.add_widget(box)
        self.add_widget(_mono(L(text=cum, font_size=dp(13) if not header else fs,
                                color=cum_color, halign="right",
                                size_hint=(None, 1), width=dp(110))))

    def _upd(self, *a):
        self._rect.pos = self.pos
        self._rect.size = self.size


FEE = 0.0005
MMR = 0.01


def calc_grid(C, Pl, Ph, Po, N, gtype, q, fee=FEE, mmr=MMR):
    eps = 1e-9
    if gtype == "geo":
        r = (Ph / Pl) ** (1.0 / N)
        lines = [Pl * (r ** i) for i in range(N + 1)]
    else:
        step = (Ph - Pl) / N
        lines = [Pl + step * i for i in range(N + 1)]
    sells = [p for p in lines if p > Po + eps]
    buys = [p for p in lines if p < Po - eps]
    M, Bc = len(sells), len(buys)
    if M < 1 or Bc < 1:
        raise ValueError("触发价必须在最低价和最高价之间,且上下都要有格子")
    sumS = sum(sells)
    sumB = sum(buys)
    Q0 = M * q
    cost0 = Q0 * Po
    buy_fee = cost0 * fee
    sellT = q * sumS
    net = sellT - cost0 - buy_fee - sellT * fee
    # 路径法爆仓:从触发价下跌,逐格吃买单,权益首次触线处
    buys_desc = sorted(buys, reverse=True)
    Q, cost, fees_paid = Q0, Q0 * Po, buy_fee
    found = None
    if not (C - fees_paid <= mmr * Q * Po):
        for Pb in buys_desc:
            avg = cost / Q
            if C + Q * (Pb - avg) - fees_paid <= mmr * Q * Pb:
                found = (Q * avg - C + fees_paid) / (Q * (1 - mmr))
                break
            Q += q
            cost += q * Pb
            fees_paid += q * Pb * fee
        if found is None:
            avg = cost / Q
            found = (Q * avg - C + fees_paid) / (Q * (1 - mmr))
    else:
        found = Po
    Qf = (M + Bc) * q
    avgF = (M * Po + sumB) / (M + Bc)
    eq_bottom = C + Qf * (Pl - avgF) - buy_fee - sumB * q * fee
    return {
        "M": M, "B": Bc, "lines": N + 1,
        "Q0": Q0, "cost0": cost0, "sellT": sellT,
        "feeT": buy_fee + sellT * fee,
        "net": net, "roe": net / C * 100.0,
        "liq": found, "eq_bottom": eq_bottom,
        "leff": Q0 * Po / C,
        "sells": sells,
    }


class GridApp(App):
    def build(self):
        self.title = "网格交易收益计算器"
        try:
            # below_target: 键盘弹起时只把当前输入框顶到键盘上方,
            # 用 pan 会把整个窗口顶起导致顶部输入框被推出屏幕
            Window.softinput_mode = "below_target"
        except Exception:
            pass
        self._mode = self._load_mode()
        self._theme = self._resolve(self._mode)
        apply_theme(self._theme)
        Window.clearcolor = BG
        self._root = BoxLayout(orientation="vertical")
        self._tab = "calc"
        self._refresh()
        return self._root

    def _refresh(self):
        _saved_inp = {_k: _t.text
                      for _k, _t in getattr(self, "inp", {}).items()}
        _gt = getattr(self, "gtype", None)
        _saved_g = _gt.text if _gt else "等比网格"
        _ft = getattr(self, "fee_inp", None)
        _saved_fee = _ft.text if _ft is not None else None
        _mt = getattr(self, "mmr_inp", None)
        _saved_mmr = _mt.text if _mt is not None else None
        _saved_tab = getattr(self, "_tab", "calc")
        self._root.clear_widgets()
        self._root.add_widget(self._build_head())
        self._body = BoxLayout()
        self._root.add_widget(self._body)
        self._calc_page = self._build_calc_page()
        self._settings_page = self._build_settings_page()
        for _k, _t in _saved_inp.items():
            if _k in self.inp:
                self.inp[_k].text = _t
        if getattr(self, "gtype", None) is not None:
            self.gtype.text = _saved_g
        if _saved_fee is not None:
            self.fee_inp.text = _saved_fee
        if _saved_mmr is not None:
            self.mmr_inp.text = _saved_mmr
        self._tabbar = BoxLayout(orientation="horizontal", size_hint_y=None,
                                 height=dp(64), padding=[dp(12), dp(8)],
                                 spacing=dp(10))
        with self._tabbar.canvas.before:
            Color(*BORDER)
            _tl = Line(points=[0, 0, 0, 0], width=1)

        def _upd_tl(*a):
            _tl.points = [self._tabbar.x, self._tabbar.top,
                          self._tabbar.right, self._tabbar.top]

        self._tabbar.bind(pos=_upd_tl, size=_upd_tl)
        self._root.add_widget(self._tabbar)
        self._show_tab(_saved_tab)
        try:
            if any(t.text.strip() for t in self.inp.values()):
                self.on_calc()
        except Exception:
            pass

    def _show_tab(self, name):
        self._tab = name
        self._body.clear_widgets()
        self._body.add_widget(self._calc_page if name == "calc"
                              else self._settings_page)
        self._tabbar.clear_widgets()
        for _name, _label in (("calc", "计算"), ("setup", "设置")):
            _b = B(text=_label, font_size=dp(15), height=dp(48),
                   ghost=(_name != self._tab))
            _b.bind(on_press=lambda _i, _n=_name: self._show_tab(_n))
            self._tabbar.add_widget(_b)

    def _build_head(self):

        head = BoxLayout(orientation="horizontal", size_hint_y=None,
                         height=dp(64), padding=[dp(14), dp(8)],
                         spacing=dp(10))
        _bw = BoxLayout(orientation="vertical", size_hint=(None, 1),
                        width=dp(36))
        _bw.add_widget(Widget())
        _bw.add_widget(Pill(text="网", bg=BLUE, fg=(1, 1, 1, 1),
                            radius=dp(8), size=(dp(36), dp(36))))
        _bw.add_widget(Widget())
        head.add_widget(_bw)
        _tt = BoxLayout(orientation="vertical", spacing=0)
        _tt.add_widget(L(text="网格交易收益计算器", font_size=dp(17),
                         color=INK, size_hint_y=None, height=dp(26)))
        _tt.add_widget(L(text="GRID CALC · 等比 / 等差", font_size=dp(11),
                         color=FAINT, size_hint_y=None, height=dp(16)))
        head.add_widget(_tt)
        with head.canvas.before:
            Color(*BORDER)
            _hl = Line(points=[0, 0, 0, 0], width=1)

        def _upd_hl(*a):
            _hl.points = [head.x, head.y, head.right, head.y]

        head.bind(pos=_upd_hl, size=_upd_hl)
        return head

    def _build_calc_page(self):
        _sc = ScrollView(bar_width=0)
        page = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(12),
                         size_hint_y=None)
        page.bind(minimum_height=page.setter("height"))

        form_card = Card()
        form_card.add_widget(H(text="输入参数"))
        form = GridLayout(cols=2, spacing=dp(8), size_hint_y=None)
        form.bind(minimum_height=form.setter("height"))
        self.inp = {}

        def row(name, key, default, hint=""):
            lb = L(text=name, color=SUB, font_size=dp(13),
                   size_hint_y=None, height=dp(48))
            ti = T(text=default, hint_text=hint)
            ti.bind(text=lambda *a: self._auto())
            form.add_widget(lb)
            form.add_widget(ti)
            self.inp[key] = ti

        row("总投入", "C", "", "USDT")
        row("杠杆倍率", "L", "", "倍")
        row("最低价", "Pl", "", "USDT")
        row("最高价", "Ph", "", "USDT")
        row("触发价", "Po", "", "USDT")
        row("网格数量", "N", "", "整数")
        row("每格数量", "q", "", "BTC")
        form_card.add_widget(form)
        page.add_widget(form_card)

        res_card = Card()
        res_card.add_widget(H(text="计算结果"))
        self.hero = _mono(L(text="--", font_size=dp(32), halign="center",
                             size_hint_y=None, height=dp(56)))
        res_card.add_widget(self.hero)
        self.hero_sub = L(text="输入参数后点计算", font_size=dp(13),
                          color=SUB, halign="center",
                          size_hint_y=None, height=dp(24))
        res_card.add_widget(self.hero_sub)
        stats = GridLayout(cols=2, spacing=dp(8), size_hint_y=None,
                           height=dp(104))
        self.stat_vals = {}
        for _key, _name in (("liq", "爆仓价"), ("roe", "收益率"),
                            ("pos", "初始持仓"), ("sell", "卖出总额")):
            _cell = BoxLayout(orientation="vertical", size_hint_y=None,
                              height=dp(48))
            _cell.add_widget(L(text=_name, font_size=dp(11), color=SUB,
                               size_hint_y=None, height=dp(18)))
            _v = _mono(L(text="--", font_size=dp(15), size_hint_y=None,
                          height=dp(30)))
            _cell.add_widget(_v)
            stats.add_widget(_cell)
            self.stat_vals[_key] = _v
        res_card.add_widget(stats)
        page.add_widget(res_card)

        det_card = Card()
        det_card.add_widget(H(text="网格明细"))
        self.det_sum = D(text="明细会在计算后显示", font_size=dp(13),
                         color=SUB)
        det_card.add_widget(self.det_sum)
        self.rows = BoxLayout(orientation="vertical", spacing=0,
                              size_hint_y=None)
        self.rows.bind(minimum_height=self.rows.setter("height"))
        det_card.add_widget(self.rows)
        page.add_widget(det_card)
        _sc.add_widget(page)
        return _sc

    def _build_settings_page(self):
        _sc = ScrollView(bar_width=0)
        _pg = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(12),
                        size_hint_y=None)
        _pg.bind(minimum_height=_pg.setter("height"))

        _tc = Card()
        _tc.add_widget(H(text="外观"))
        _trow = BoxLayout(orientation="horizontal", spacing=dp(8),
                          size_hint_y=None, height=dp(48))
        for _m, _label in (("follow", "自动"), ("light", "浅色"),
                           ("dark", "深色")):
            _b = B(text=_label, font_size=dp(15), height=dp(48),
                   ghost=(_m != self._mode))
            _b.bind(on_press=lambda _i, _mm=_m: self.set_mode(_mm))
            _trow.add_widget(_b)
        _tc.add_widget(_trow)
        _pg.add_widget(_tc)

        _fc = Card()
        _fc.add_widget(H(text="费率"))
        _fg = GridLayout(cols=2, spacing=dp(8), size_hint_y=None)
        _fg.bind(minimum_height=_fg.setter("height"))
        _fg.add_widget(L(text="手续费率 %", color=SUB, font_size=dp(13),
                         size_hint_y=None, height=dp(48)))
        self.fee_inp = T(text="0.05")
        self.fee_inp.bind(text=lambda *a: self._auto())
        _fg.add_widget(self.fee_inp)
        _fg.add_widget(L(text="维持保证金率 %", color=SUB, font_size=dp(13),
                         size_hint_y=None, height=dp(48)))
        self.mmr_inp = T(text="100")
        self.mmr_inp.bind(text=lambda *a: self._auto())
        _fg.add_widget(self.mmr_inp)
        _fc.add_widget(_fg)
        _pg.add_widget(_fc)

        _ac = Card()
        _ac.add_widget(H(text="关于"))
        _ac.add_widget(D(text="网格交易收益计算器 v2.0\n等比 / 等差网格 · 路径法爆仓价 · 离线运行",
                         font_size=dp(12), color=SUB))
        _pg.add_widget(_ac)
        _sc.add_widget(_pg)
        return _sc

    _CYCLE = {"follow": "light", "light": "dark", "dark": "follow"}
    _SHOW = {"follow": "自动", "light": "浅色", "dark": "深色"}

    def _store(self):
        try:
            from kivy.storage.jsonstore import JsonStore
            return JsonStore(os.path.join(self.user_data_dir,
                                          "gridcalc.json"))
        except Exception:
            return None

    def _load_mode(self):
        try:
            _st = self._store()
            if _st is not None and _st.exists("ui"):
                _m = _st.get("ui").get("theme", "follow")
                if _m in ("follow", "light", "dark"):
                    return _m
        except Exception:
            pass
        return "follow"

    def _resolve(self, mode):
        if mode == "dark":
            return "dark"
        if mode == "light":
            return "light"
        return "light" if _system_dark() is False else "dark"

    def set_mode(self, mode):
        self._mode = mode
        try:
            _st = self._store()
            if _st is not None:
                _st.put("ui", theme=self._mode)
        except Exception:
            pass
        _new = self._resolve(self._mode)
        if _new != self._theme:
            apply_theme(_new)
            self._theme = _new
        Window.clearcolor = BG
        self._refresh()

    def _get(self, key, name, is_int=False):
        t = self.inp[key].text.strip()
        if not t:
            raise ValueError("%s还没有填" % name)
        try:
            v = float(t)
        except Exception:
            raise ValueError("%s不是有效数字" % name)
        return int(v) if is_int else v

    def on_calc(self, *a):
        try:
            C = self._get("C", "总投入")
            L = self._get("L", "杠杆倍率")
            Pl = self._get("Pl", "最低价")
            Ph = self._get("Ph", "最高价")
            Po = self._get("Po", "触发价")
            N = self._get("N", "网格数量", is_int=True)
            q = self._get("q", "每格数量")
            if not C > 0:
                raise ValueError("总投入必须大于0")
            if not L >= 1:
                raise ValueError("杠杆倍率必须≥1")
            if not Pl > 0:
                raise ValueError("最低价必须大于0")
            if not Ph > Pl:
                raise ValueError("最高价必须大于最低价")
            if not Pl < Po < Ph:
                raise ValueError("触发价必须在最低价和最高价之间")
            if not N >= 1:
                raise ValueError("网格数量必须≥1")
            if not q > 0:
                raise ValueError("每格数量必须大于0")
            _ft = self.fee_inp.text.strip()
            if not _ft:
                raise ValueError("手续费率还没有填")
            try:
                fee = float(_ft) / 100.0
            except Exception:
                raise ValueError("手续费率不是有效数字")
            if not fee >= 0:
                raise ValueError("手续费率不能为负")
            _mt = self.mmr_inp.text.strip()
            if not _mt:
                raise ValueError("维持保证金率还没有填")
            try:
                mmr = float(_mt) / 100.0
            except Exception:
                raise ValueError("维持保证金率不是有效数字")
            if not mmr >= 0:
                raise ValueError("维持保证金率不能为负")
            g = "geo"
            r = calc_grid(C, Pl, Ph, Po, N, g, q, fee, mmr)
            self.hero.color = TEAL if r["net"] >= 0 else RED
            self.hero.text = "%+.2f USDT" % r["net"]
            self.hero_sub.color = SUB
            self.hero_sub.text = ("收益率 %+.2f%% · %d卖%d买 · 见底权益约%.2fU"
                                  % (r["roe"], r["M"], r["B"], r["eq_bottom"]))
            self.stat_vals["liq"].text = "%.2f" % r["liq"]
            self.stat_vals["roe"].text = "%+.2f%%" % r["roe"]
            self.stat_vals["pos"].text = "%.4f" % r["Q0"]
            self.stat_vals["sell"].text = "%.2f" % r["sellT"]
            self.det_sum.color = INK
            self.det_sum.text = ("买%d/卖%d/线%d · 持仓%.6f · 成本%.2f"
                                 % (r["B"], r["M"], r["lines"], r["Q0"],
                                    r["cost0"]))
            buy_fee = r["cost0"] * fee
            cumSell = 0.0
            self.rows.clear_widgets()
            self.rows.add_widget(Row("#", "网格价", "方向", None, None,
                                      "累计净收益", FAINT, False, header=True))
            zebra = False
            for i, p in enumerate(sorted(
                    [x for x in self._lines(Pl, Ph, N, g) if x < Po - 1e-9]
                    + [x for x in self._lines(Pl, Ph, N, g) if x > Po + 1e-9])):
                if p < Po:
                    amt = q * p
                    self.rows.add_widget(Row(
                        str(i + 1), "%.2f" % p, "买入", TEAL_BG, TEAL,
                        "%.2f" % amt, SUB, zebra))
                else:
                    amt = q * p
                    cumSell += amt
                    cum = cumSell - r["cost0"] - buy_fee - cumSell * fee
                    self.rows.add_widget(Row(
                        str(i + 1), "%.2f" % p, "卖出", BLUE_BG, BLUE,
                        "%+.2f" % cum, TEAL if cum >= 0 else RED, zebra))
                zebra = not zebra
        except Exception as e:
            _msg = str(e)
            if "还没有填" in _msg:
                self.hero.color = INK
                self.hero.text = "--"
                self.hero_sub.color = SUB
                self.hero_sub.text = "输入参数后点计算"
                for _v in self.stat_vals.values():
                    _v.text = "--"
                self.det_sum.color = SUB
                self.det_sum.text = "明细会在计算后显示"
                self.rows.clear_widgets()
                return
            self.hero.color = RED
            self.hero.text = "出错"
            self.hero_sub.color = RED
            self.hero_sub.text = _msg
            for _v in self.stat_vals.values():
                _v.text = "--"

    def _auto(self, *a):
        try:
            self.on_calc()
        except Exception:
            pass

    @staticmethod
    def _lines(Pl, Ph, N, g):
        if g == "geo":
            r = (Ph / Pl) ** (1.0 / N)
            return [Pl * (r ** i) for i in range(N + 1)]
        step = (Ph - Pl) / N
        return [Pl + step * i for i in range(N + 1)]


if __name__ == "__main__":
    GridApp().run()
