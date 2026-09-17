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
from kivy.core.window import Window

# 安卓系统自带中文字体,按优先级选用,找不到则回退默认字体
FONT = None
for _p in ("/system/fonts/NotoSansCJK-Regular.ttc",
           "/system/fonts/NotoSansSC-Regular.otf",
           "/system/fonts/DroidSansFallback.ttf"):
    if os.path.exists(_p):
        FONT = _p
        break


class L(Label):
    def __init__(self, **k):
        if FONT:
            k.setdefault("font_name", FONT)
        k.setdefault("font_size", dp(15))
        k.setdefault("halign", "left")
        k.setdefault("valign", "middle")
        super().__init__(**k)
        self.bind(size=self._wrap)

    def _wrap(self, *a):
        self.text_size = (self.width, None)


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
        if FONT:
            k.setdefault("font_name", FONT)
        k.setdefault("font_size", dp(16))
        k.setdefault("multiline", False)
        k.setdefault("input_filter", "float")
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(46))
        super().__init__(**k)


class B(Button):
    def __init__(self, **k):
        if FONT:
            k.setdefault("font_name", FONT)
        k.setdefault("font_size", dp(17))
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(52))
        super().__init__(**k)


class SO(SpinnerOption):
    def __init__(self, **k):
        if FONT:
            k.setdefault("font_name", FONT)
        super().__init__(**k)


class S(Spinner):
    def __init__(self, **k):
        if FONT:
            k.setdefault("font_name", FONT)
        k.setdefault("option_cls", SO)
        k.setdefault("font_size", dp(16))
        k.setdefault("size_hint_y", None)
        k.setdefault("height", dp(46))
        super().__init__(**k)


FEE = 0.0005
MMR = 0.01


def calc_grid(C, Pl, Ph, Po, N, gtype, q):
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
    buy_fee = cost0 * FEE
    sellT = q * sumS
    net = sellT - cost0 - buy_fee - sellT * FEE
    # 路径法爆仓:从触发价下跌,逐格吃买单,权益首次触线处
    buys_desc = sorted(buys, reverse=True)
    Q, cost, fees_paid = Q0, Q0 * Po, buy_fee
    found = None
    if not (C - fees_paid <= MMR * Q * Po):
        for Pb in buys_desc:
            avg = cost / Q
            if C + Q * (Pb - avg) - fees_paid <= MMR * Q * Pb:
                found = (Q * avg - C + fees_paid) / (Q * (1 - MMR))
                break
            Q += q
            cost += q * Pb
            fees_paid += q * Pb * FEE
        if found is None:
            avg = cost / Q
            found = (Q * avg - C + fees_paid) / (Q * (1 - MMR))
    else:
        found = Po
    Qf = (M + Bc) * q
    avgF = (M * Po + sumB) / (M + Bc)
    eq_bottom = C + Qf * (Pl - avgF) - buy_fee - sumB * q * FEE
    return {
        "M": M, "B": Bc, "lines": N + 1,
        "Q0": Q0, "cost0": cost0, "sellT": sellT,
        "feeT": buy_fee + sellT * FEE,
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
        root = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
        form_box = BoxLayout(orientation="vertical", size_hint_y=0.52)
        sc = ScrollView()
        form = GridLayout(cols=2, spacing=dp(8), size_hint_y=None)
        form.bind(minimum_height=form.setter("height"))
        self.inp = {}

        def row(name, key, default):
            lb = L(text=name, size_hint_y=None, height=dp(46))
            ti = T(text=default)
            form.add_widget(lb)
            form.add_widget(ti)
            self.inp[key] = ti

        row("总投入 USDT", "C", "")
        row("杠杆倍率", "L", "")
        row("最低价", "Pl", "")
        row("最高价", "Ph", "")
        row("触发价", "Po", "")
        row("网格数量 N", "N", "")
        row("每格数量", "q", "")
        form.add_widget(L(text="网格类型", size_hint_y=None, height=dp(46)))
        self.gtype = S(text="等比网格", values=("等比网格", "等差网格"))
        form.add_widget(self.gtype)
        sc.add_widget(form)
        form_box.add_widget(sc)
        root.add_widget(form_box)

        btn = B(text="计 算", background_color=(0.09, 0.36, 1, 1))
        btn.bind(on_press=self.on_calc)
        root.add_widget(btn)

        self.res = L(text="输入参数后点计算", font_size=dp(17),
                      size_hint_y=None, height=dp(150))
        root.add_widget(self.res)

        dsc = ScrollView(size_hint_y=0.3)
        self.det = D(text="", font_size=dp(13))
        dsc.add_widget(self.det)
        root.add_widget(dsc)
        return root

    def on_calc(self, *a):
        try:
            C = float(self.inp["C"].text)
            L = float(self.inp["L"].text)
            Pl = float(self.inp["Pl"].text)
            Ph = float(self.inp["Ph"].text)
            Po = float(self.inp["Po"].text)
            N = int(float(self.inp["N"].text))
            q = float(self.inp["q"].text)
            if not (C > 0 and L >= 1 and Pl > 0 and Ph > Pl
                    and Pl < Po < Ph and N >= 1 and q > 0):
                raise ValueError("参数范围不对,检查后重试")
            g = "geo" if self.gtype.text.startswith("等比") else "arith"
            r = calc_grid(C, Pl, Ph, Po, N, g, q)
            self.res.text = (
                "最终净收益: %+.2f USDT\n收益率: %+.2f%%\n爆仓价: %.2f"
                % (r["net"], r["roe"], r["liq"]))
            ds = ["序号,网格价,方向"]
            for i, p in enumerate(sorted(
                    [x for x in self._lines(Pl, Ph, N, g) if x < Po - 1e-9]
                    + [x for x in self._lines(Pl, Ph, N, g) if x > Po + 1e-9])):
                d = "买入" if p < Po else "卖出"
                ds.append("%d,%.2f,%s" % (i + 1, p, d))
            self.det.text = ("买%d/卖%d/线%d,持仓%.6f,成本%.2f\n见底权益约%.2fU\n"
                             % (r["B"], r["M"], r["lines"], r["Q0"],
                                r["cost0"], r["eq_bottom"]) + "\n".join(ds))
        except Exception as e:
            self.res.text = "出错:" + str(e)

    @staticmethod
    def _lines(Pl, Ph, N, g):
        if g == "geo":
            r = (Ph / Pl) ** (1.0 / N)
            return [Pl * (r ** i) for i in range(N + 1)]
        step = (Ph - Pl) / N
        return [Pl + step * i for i in range(N + 1)]


if __name__ == "__main__":
    GridApp().run()
