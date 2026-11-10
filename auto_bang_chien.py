#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TOOL TỰ ĐỘNG BANG CHIẾN (điện thoại Android nối máy tính qua ADB)

Cách hoạt động: chụp màn hình điện thoại -> nhận diện đang ở màn hình nào
(bằng ảnh mẫu trong thư mục templates/) -> bấm đúng nút theo kịch bản của bạn.

Chạy:           python auto_bang_chien.py
Thử nhận diện:  python auto_bang_chien.py --detect anh_chup.jpg   (không cần điện thoại)
Xem thêm:       python auto_bang_chien.py --help

Toạ độ trong file này đều tính theo màn hình chuẩn 2400x1080 (ngang).
Nếu điện thoại có độ phân giải khác (cùng tỉ lệ), tool tự quy đổi.
"""
import argparse
import logging
import os
import subprocess
import sys
import threading
import time
import zlib

import cv2
import numpy as np

# =====================================================================
# CẤU HÌNH (chỉnh ở đây nếu cần)
# =====================================================================
REF_W, REF_H = 2400, 1080          # độ phân giải chuẩn của ảnh mẫu
HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_DIR = os.path.join(HERE, "templates")
DEBUG_DIR = os.path.join(HERE, "debug")

MATCH_THRESHOLD = 0.75     # độ giống tối thiểu (0..1) để coi là nhận ra màn hình
MATCH_MARGIN = 90          # vùng tìm quanh vị trí gốc của ảnh mẫu (px chuẩn)
# Hai nút "Tấn công" (màn Bang chiến và bảng chọn deck) giống hệt nhau nhưng lệch ~70px,
# nên vùng tìm của chúng phải hẹp để không nhận nhầm nút này thành nút kia.
MARGIN_OVERRIDE = {"main_attack": 25, "dialog_attack": 25, "grid_defeated": 40}

# Vị trí gốc của từng ảnh mẫu trong ảnh 2400x1080: (x0, y0, x1, y1)
TEMPLATES = {
    "game_chon":       (237, 31, 579, 95),       # tiêu đề "Game chọn"
    "main_attack":     (1347, 967, 1668, 1028),  # nút "Tấn công" (màn Bang chiến, ảnh 2)
    "main_newguild":   (1798, 967, 2150, 1028),  # nút "Bang hội mới"
    "grid_clock":      (1892, 40, 2120, 86),     # đồng hồ "Còn lại" (màn lưới 30 ô, ảnh 4)
    "grid_label":      (992, 135, 1133, 171),    # chữ "Tiến trình"
    "dialog_close":    (834, 899, 1209, 973),    # nút "Đóng" (bảng chọn deck, ảnh 5)
    "dialog_attack":   (1316, 895, 1668, 963),   # nút "Tấn công" (bảng chọn deck)
    "grid_defeated":   (895, 566, 1612, 610),    # dòng "Hãy chuyển sang bang hội tiếp theo."
    "battle_pause":    (2281, 31, 2370, 104),    # nút tạm dừng khi đang đánh (ảnh 7)
    "result_mainmenu": (1140, 964, 1370, 1028),  # nút "Main Menu" (màn kết quả, ảnh 8)
}

# ---- Các điểm bấm (x, y) ----
TAP_BANG_CHIEN_CARD = (888, 581)    # ô "Bang chiến" ở màn Game chọn (ảnh 1)
TAP_BACK = (181, 61)                # nút mũi tên quay lại góc trên trái
TAP_MAIN_ATTACK = (1508, 994)       # nút xanh dương "Tấn công" (ảnh 2)
TAP_MAIN_NEWGUILD = (1967, 994)     # nút tím "Bang hội mới" (ảnh 2)
TAP_DIALOG_ATTACK = (1492, 933)     # nút "Tấn công" trong bảng chọn deck (ảnh 6)
TAP_DIALOG_CLOSE = (1018, 933)      # nút "Đóng"
DECK_RADIO_X = 768                  # cột nút tròn chọn deck
DECK_ROW_Y = [505, 633, 762]        # hàng Deck 1, 2, 3 (ưu tiên 1 -> 2 -> 3)
DECK_SAMPLE_X = (827, 1071)         # vùng đo độ sáng để biết deck còn dùng được không
DECK_CHECK_X = (735, 815)           # vùng quanh nút tròn, nơi hiện dấu tích đỏ khi đã chọn deck
DECK_CHECK_MIN = 60                 # số điểm ảnh đỏ tối thiểu để coi là đã chọn (dấu tích thật ~200)
BATTLE_CARDS = [(1018, 979), (1286, 979), (1584, 979)]  # 3 ô nhân vật khi đánh (ảnh 7)
TAP_RESULT_LOBBY = (946, 994)       # nút "Lobby" (ảnh 8)
TAP_RESULT_CONTINUE = (1558, 994)   # nút "Tiếp tục" (ảnh 8)

# ---- Thanh tiến trình (x0, y0, x1, y1) phần bên trong thanh ----
MAIN_BAR = (1578, 864, 1899, 883)   # ô đỏ ở ảnh 2
GRID_BAR = (1146, 143, 1468, 162)   # thanh trên lưới 30 ô
BAR_FULL_RATIO = 0.98               # đầy từ 98% trở lên coi là đầy

# ---- Lưới 30 ô nhà ----
CELL_DARK_RATIO = 0.35    # tỉ lệ điểm ảnh tối >= mức này: ô đang đánh / đã clear (bỏ qua)
CELL_LIGHT_RATIO = 0.30   # tỉ lệ điểm ảnh trắng >= mức này: ô ĐÁNH ĐƯỢC (xanh lá ở ảnh 4)
DECK_LIGHT_VALUE = 150    # độ sáng trung bình của hàng deck: >= là còn dùng được

# ---- Nhịp chạy ----
BATTLE_TAP_ROUNDS = 2     # mỗi lượt gửi: bấm lần lượt 3 ô nhân vật, lặp bấy nhiêu vòng
BATTLE_MAX_INFLIGHT = 1   # số lượt bấm được xếp hàng trên máy cùng lúc (cao hơn = bấm dày hơn)
IDLE_WAIT = 3.0           # nghỉ giữa các lần kiểm tra khi chưa có nhà nào để đánh (giây)
CHANGE_THRESHOLD = 25.0   # độ khác biệt (0..255) để coi là màn hình đã chuyển sau khi bấm
SETTLE = 0.12             # chờ thêm khi màn hình đang chuyển cảnh (giây)

# Tên trạng thái
LOBBY_SELECT, MAIN, GRID, DIALOG, BATTLE, RESULT, UNKNOWN = (
    "LOBBY_SELECT", "MAIN", "GRID", "DIALOG", "BATTLE", "RESULT", "UNKNOWN")

log = logging.getLogger("bot")


# =====================================================================
# ADB
# =====================================================================
class Adb:
    """Điều khiển điện thoại. Bấm đi qua MỘT phiên `adb shell` giữ mở (không tốn thời gian
    gọi lại adb mỗi lần) và gửi đi không chờ; chụp màn hình dạng thô, tự chọn cách nhanh nhất."""

    def __init__(self, adb_path="adb", serial=None):
        self.base = [adb_path] + (["-s", serial] if serial else [])
        self.w, self.h = REF_W, REF_H
        self.method = "raw"          # raw | gz | png (calibrate() sẽ chọn cách nhanh nhất)
        self.last_ms = 0.0
        self._p = None
        self._inflight = 0
        self._lock = threading.Lock()

    def _run(self, *args, timeout=30):
        return subprocess.run(self.base + list(args), capture_output=True, timeout=timeout)

    def check_device(self):
        out = self._run("devices").stdout.decode(errors="ignore")
        lines = [l for l in out.splitlines()[1:] if l.strip()]
        ok = [l for l in lines if l.split()[-1] == "device"]
        if not ok:
            raise RuntimeError(
                "Không thấy điện thoại. Chạy `adb devices`, bật Gỡ lỗi USB và bấm 'Cho phép' trên máy.\n"
                "Kết quả hiện tại:\n" + out)
        if len(ok) > 1 and len(self.base) == 1:
            raise RuntimeError("Có nhiều thiết bị, hãy chọn một bằng --serial. Danh sách:\n" + out)

    # ---------------- chụp màn hình ----------------
    @staticmethod
    def _decode_raw(raw):
        """Giải mã ảnh thô của `screencap` (không có -p): 12 hoặc 16 byte đầu là w, h, định dạng."""
        if raw is None or len(raw) < 64:
            return None
        w, h, fmt = (int(v) for v in np.frombuffer(raw[:12], "<u4"))
        header = len(raw) - w * h * 4
        if not (100 <= w <= 10000 and 100 <= h <= 10000 and 0 <= header <= 64 and fmt in (1, 2, 5)):
            return None
        buf = np.frombuffer(raw, np.uint8, count=w * h * 4, offset=header).reshape(h, w, 4)
        return cv2.cvtColor(buf, cv2.COLOR_BGRA2BGR if fmt == 5 else cv2.COLOR_RGBA2BGR)

    def _shot(self, method):
        try:
            if method == "raw":
                return self._decode_raw(self._run("exec-out", "screencap", timeout=20).stdout)
            if method == "gz":      # nén trên máy rồi giải nén ở đây: nhẹ đường truyền USB
                data = self._run("exec-out", "screencap | gzip -1", timeout=20).stdout
                return self._decode_raw(zlib.decompress(data, 31))
            raw = self._run("exec-out", "screencap", "-p", timeout=20).stdout
            return cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR) if raw else None
        except Exception:
            return None

    def calibrate(self):
        """Đo 3 cách chụp màn hình, chọn cách nhanh nhất. Trả về {cách: giây}."""
        res = {}
        for m in ("raw", "gz", "png"):
            ts = []
            for _ in range(3):
                t = time.time()
                if self._shot(m) is None:
                    ts = None
                    break
                ts.append(time.time() - t)
            if ts:
                res[m] = sorted(ts)[1]
        if not res:
            raise RuntimeError("Không chụp được màn hình điện thoại.")
        self.method = min(res, key=res.get)
        return res

    def screenshot(self):
        for attempt in range(3):
            t = time.time()
            img = self._shot(self.method)
            if img is None:                              # cách đang dùng lỗi -> thử cách khác
                for m in ("raw", "png"):
                    img = self._shot(m)
                    if img is not None:
                        self.method = m
                        break
            if img is not None:
                self.h, self.w = img.shape[:2]
                self.last_ms = (time.time() - t) * 1000
                return img
            time.sleep(0.3)
        raise RuntimeError("Không chụp được màn hình điện thoại.")

    # ---------------- bấm ----------------
    def _dev(self, p):
        return int(p[0] * self.w / REF_W), int(p[1] * self.h / REF_H)

    def _start_shell(self):
        self._stop_shell()
        with self._lock:
            self._inflight = 0
        p = subprocess.Popen(self.base + ["shell"], stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self._p = p
        threading.Thread(target=self._reader, args=(p,), daemon=True).start()

    def _reader(self, p):
        try:
            for line in iter(p.stdout.readline, b""):
                if b"__done__" in line:                  # máy báo đã bấm xong một lượt
                    with self._lock:
                        if self._p is p and self._inflight > 0:
                            self._inflight -= 1
        except Exception:
            pass

    def _stop_shell(self):
        p, self._p = self._p, None
        if p is not None:
            try:
                p.kill()
            except Exception:
                pass

    def inflight(self):
        """Số lượt bấm đã gửi nhưng máy chưa làm xong."""
        with self._lock:
            return self._inflight

    def abort_taps(self):
        """Huỷ các lượt bấm còn xếp hàng (dùng ngay khi hết trận để không bấm lạc sang màn kết quả)."""
        self._stop_shell()

    def fire_cmd(self, cmd):
        for _ in range(2):
            try:
                if self._p is None or self._p.poll() is not None:
                    self._start_shell()
                with self._lock:
                    self._inflight += 1
                self._p.stdin.write((cmd + '; echo __d""one__\n').encode())
                self._p.stdin.flush()
                return
            except Exception:
                self._stop_shell()
        self._run("shell", cmd)                           # dự phòng: gọi adb từng lệnh

    def fire(self, points):
        """Gửi lệnh bấm đi ngay, không chờ máy xử lý xong."""
        self.fire_cmd("; ".join("input tap %d %d" % self._dev(p) for p in points))

    def wait_idle(self, timeout=10.0):
        t0 = time.time()
        while self.inflight() > 0 and time.time() - t0 < timeout:
            time.sleep(0.01)

    def close(self):
        self._stop_shell()


# =====================================================================
# NHẬN DIỆN
# =====================================================================
def to_ref(frame):
    if frame.shape[1] != REF_W or frame.shape[0] != REF_H:
        frame = cv2.resize(frame, (REF_W, REF_H), interpolation=cv2.INTER_AREA)
    return frame


def cell_rect(i):
    """Hình chữ nhật của ô nhà số i (1..30) trong ảnh 2400x1080."""
    r, c = divmod(i - 1, 5)
    top = 210 + 130.4 * r
    left = (471 if r % 2 == 0 else 298) + 349.8 * c
    return int(left), int(top), int(left + 338), int(top + 119)


def cell_center(i):
    x0, y0, x1, y1 = cell_rect(i)
    return (x0 + x1) // 2, (y0 + y1) // 2


class Vision:
    def __init__(self):
        self.tpl = {}
        for name in TEMPLATES:
            path = os.path.join(TEMPLATE_DIR, name + ".png")
            img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
            if img is None:
                raise FileNotFoundError("Thiếu ảnh mẫu: " + path)
            self.tpl[name] = cv2.resize(img, (img.shape[1] // 2, img.shape[0] // 2),
                                        interpolation=cv2.INTER_AREA)

    def score(self, small_gray, name):
        x0, y0, x1, y1 = TEMPLATES[name]
        m = MARGIN_OVERRIDE.get(name, MATCH_MARGIN)
        rx0, ry0 = max(0, (x0 - m) // 2), max(0, (y0 - m) // 2)
        rx1, ry1 = min(small_gray.shape[1], (x1 + m) // 2), min(small_gray.shape[0], (y1 + m) // 2)
        roi = small_gray[ry0:ry1, rx0:rx1]
        t = self.tpl[name]
        if roi.shape[0] < t.shape[0] or roi.shape[1] < t.shape[1]:
            return 0.0
        res = cv2.matchTemplate(roi, t, cv2.TM_CCOEFF_NORMED)
        return float(np.nan_to_num(res.max()))

    def score_name(self, frame, name):
        small = cv2.resize(cv2.cvtColor(to_ref(frame), cv2.COLOR_BGR2GRAY), (REF_W // 2, REF_H // 2),
                           interpolation=cv2.INTER_AREA)
        return self.score(small, name)

    def identify(self, frame):
        """Trả về (trạng thái, điểm từng ảnh mẫu)."""
        ref = to_ref(frame)
        small = cv2.resize(cv2.cvtColor(ref, cv2.COLOR_BGR2GRAY), (REF_W // 2, REF_H // 2),
                           interpolation=cv2.INTER_AREA)
        s = {n: self.score(small, n) for n in TEMPLATES}
        ok = {n: v >= MATCH_THRESHOLD for n, v in s.items()}
        if ok["result_mainmenu"]:
            st = RESULT
        elif ok["battle_pause"]:
            st = BATTLE
        elif ok["dialog_close"] or ok["dialog_attack"]:
            st = DIALOG
        elif ok["main_attack"] and not ok["grid_clock"]:
            st = MAIN
        elif ok["game_chon"]:
            st = LOBBY_SELECT
        elif ok["grid_clock"] or ok["grid_label"]:
            st = GRID
        else:
            st = UNKNOWN
        return st, s

    # ---- thanh tiến trình ----
    @staticmethod
    def bar_ratio(frame, bar):
        ref = to_ref(frame)
        x0, y0, x1, y1 = bar
        best = -1
        for y in (y0 + 4, y1 - 4):
            row = ref[y, x0:x1 + 1].astype(int)
            b, g, r = row[:, 0], row[:, 1], row[:, 2]
            gold = (r > 180) & (g > 130) & (b < 140) & (r - b > 80)
            idx = np.where(gold)[0]
            if len(idx):
                best = max(best, int(idx.max()))
        return 0.0 if best < 0 else (best + 1) / float(x1 - x0 + 1)

    # ---- lưới 30 ô ----
    @staticmethod
    def scan_cells(frame):
        """Trả về dict {số ô: 'light' | 'dark'} (ô trống không có trong dict).
        light = ô đánh được (thẻ sáng, có Lv. + tên); dark = đang tiến hành / đã Clear."""
        ref = to_ref(frame)
        out = {}
        for i in range(1, 31):
            x0, y0, x1, y1 = cell_rect(i)
            sub = ref[y0 + 6:y1 - 6, x0 + 6:x1 - 6]
            mx, mn = sub.max(axis=2), sub.min(axis=2)
            dark, white = float((mx < 75).mean()), float((mn > 200).mean())
            if white >= CELL_LIGHT_RATIO:
                out[i] = "light"
            elif dark >= CELL_DARK_RATIO:
                out[i] = "dark"
        return out

    # ---- bảng chọn deck ----
    @staticmethod
    def deck_available(frame):
        """[True/False] cho Deck 1,2,3. False = đã dùng (hiện Thắng/Thua) nên hàng tối."""
        ref = to_ref(frame)
        gray = cv2.cvtColor(ref, cv2.COLOR_BGR2GRAY)
        res = []
        for y in DECK_ROW_Y:
            res.append(float(gray[y - 18:y + 18, DECK_SAMPLE_X[0]:DECK_SAMPLE_X[1]].mean()) >= DECK_LIGHT_VALUE)
        return res


    @staticmethod
    def selected_deck(frame, avail=None):
        """Chỉ số deck (0,1,2) đang được chọn (có dấu tích đỏ), hoặc None nếu chưa chọn deck nào."""
        ref = to_ref(frame)
        for i, y in enumerate(DECK_ROW_Y):
            if avail is not None and not avail[i]:
                continue
            sub = ref[y - 26:y + 26, DECK_CHECK_X[0]:DECK_CHECK_X[1]].astype(int)
            red = (sub[..., 2] > 170) & (sub[..., 1] < 90) & (sub[..., 0] < 90)
            if int(red.sum()) >= DECK_CHECK_MIN:
                return i
        return None


# =====================================================================
# BOT
# =====================================================================
class Bot:
    def __init__(self, adb, vision, args):
        self.adb, self.v, self.args = adb, vision, args
        self.total_houses = None     # số nhà (thành viên) của bang đối thủ hiện tại
        self.blocked = set()         # ô đã hết deck để đánh, bỏ qua
        self.last_newguild = 0.0
        self.last_state = None
        self.same_count = 0
        self.unknown_count = 0
        self.battles = 0
        self.new_guilds = 0
        self.saved_debug = 0
        self.dialog_tries = 0
        self.battle_started = None
        self.battle_unknown = 0
        self.pending = None
        self.cache = None            # khung hình đã chụp sẵn sau khi bấm (khỏi chụp lại)

    # ---------- tiện ích ----------
    def tap(self, p, wait=0.0):
        if not self.args.dry_run:
            self.adb.fire([p])
        if wait:
            time.sleep(wait)

    @staticmethod
    def _small(frame):
        return cv2.resize(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), (160, 72),
                          interpolation=cv2.INTER_AREA).astype(np.int16)

    def tap_wait(self, p, frame, timeout=3.0):
        """Bấm rồi chờ đến khi màn hình mới hiện ra. Khung hình đầu tiên đã nhận ra được
        thì dùng luôn cho vòng sau (không chụp lại, tiết kiệm ~1 lần chụp mỗi lần chuyển màn)."""
        if self.args.dry_run:
            time.sleep(0.5)
            return
        before = self._small(frame)
        self.adb.fire([p])
        t0, changed = time.time(), False
        while time.time() - t0 < timeout:
            cur = self.adb.screenshot()
            if not changed and np.abs(self._small(cur) - before).mean() >= CHANGE_THRESHOLD:
                changed = True
            if changed:
                if self.v.identify(cur)[0] != UNKNOWN:
                    self.cache = cur
                    return
                time.sleep(SETTLE)                    # đang chuyển cảnh, chụp lại sau chút

    def save_unknown(self, frame):
        if self.saved_debug >= 30:
            return
        os.makedirs(DEBUG_DIR, exist_ok=True)
        path = os.path.join(DEBUG_DIR, "unknown_%s.png" % time.strftime("%H%M%S"))
        cv2.imwrite(path, frame)
        self.saved_debug += 1
        log.info("Đã lưu ảnh màn hình lạ: %s", path)

    # ---------- vòng lặp chính ----------
    def run(self):
        log.info("Bắt đầu. Nhấn Ctrl+C để dừng.")
        while True:
            if self.args.max_battles and self.battles >= self.args.max_battles:
                log.info("Đã đủ %d trận, dừng.", self.battles)
                return
            frame = self.cache if self.cache is not None else self.adb.screenshot()
            self.cache = None
            state, scores = self.v.identify(frame)

            # Màn hình lạ ngay sau BATTLE (hoạt ảnh thắng/thua) thì vẫn coi là đang đánh
            if state == UNKNOWN and self.last_state == BATTLE and self.battle_unknown < 40:
                self.battle_unknown += 1
                state = BATTLE
            elif state != UNKNOWN:
                self.battle_unknown = 0

            # Hết trận: huỷ ngay các lượt bấm còn xếp hàng để không bấm lạc vào nút ở màn kết quả
            if self.last_state == BATTLE and state != BATTLE and not self.args.dry_run:
                self.adb.abort_taps()

            if state == self.last_state:
                self.same_count += 1
            else:
                self.same_count = 0
                if self.args.timing:
                    log.info("Màn hình: %s (chụp %.0f ms)", state, self.adb.last_ms)
                else:
                    log.info("Màn hình: %s", state)
            if state == UNKNOWN:
                self.unknown_count += 1
            elif state != BATTLE:
                self.unknown_count = 0

            getattr(self, "on_" + state.lower())(frame, scores)
            self.last_state = state

    # ---------- xử lý từng màn hình ----------
    def on_lobby_select(self, frame, scores):
        self.tap_wait(TAP_BANG_CHIEN_CARD, frame)    # ảnh 1: chỉ bấm Bang chiến

    def on_main(self, frame, scores):
        ratio = self.v.bar_ratio(frame, MAIN_BAR)
        full = ratio >= BAR_FULL_RATIO
        if self.same_count == 0:
            log.info("Tiến trình bang đối thủ: %.0f%%%s", ratio * 100, " (ĐẦY)" if full else "")
        if full:
            # Đầy -> bấm "Bang hội mới" (ô tím). Chờ sau mỗi lần bấm để tránh bấm đúp.
            if time.time() - self.last_newguild < 3.0:
                time.sleep(0.2)
                return
            log.info("Bang đã clear hết -> tìm Bang hội mới")
            if not self.args.dry_run:
                self.adb.fire([TAP_MAIN_NEWGUILD])
                t0 = time.time()                     # chờ đến khi thanh tiến trình về 0 (ghép xong)
                while time.time() - t0 < 4.0:
                    if self.v.bar_ratio(self.adb.screenshot(), MAIN_BAR) < BAR_FULL_RATIO:
                        break
                time.sleep(0.2)
            else:
                time.sleep(0.5)
            self.last_newguild = time.time()
            self.new_guilds += 1
            self.blocked.clear()
            self.total_houses = None
        else:
            # Chưa đầy -> bấm "Tấn công" (ô xanh dương) vào lưới 30 ô
            self.tap_wait(TAP_MAIN_ATTACK, frame)

    def grid_done(self, frame):
        """Bang đối thủ đã bị đánh hết (thanh đầy hoặc hiện banner 'Đã bị đánh bại')."""
        return (self.v.bar_ratio(frame, GRID_BAR) >= BAR_FULL_RATIO
                or self.v.score_name(frame, "grid_defeated") >= MATCH_THRESHOLD)

    def on_grid(self, frame, scores):
        # Phải kiểm tra TRƯỚC khi quét ô: banner trắng đè lên lưới làm ô trống trông như ô đánh được.
        if self.grid_done(frame):
            log.info("Bang đối thủ đã bị đánh bại -> quay lại để ghép bang hội mới")
            self.tap_wait(TAP_BACK, frame)
            return
        cells = self.v.scan_cells(frame)
        targets = [i for i, k in cells.items() if k == "light" and i not in self.blocked]
        # Ảnh có thể chụp lúc màn hình đang hiện dần -> quét lại một lần cho chắc
        if not targets:
            time.sleep(0.4)
            frame = self.adb.screenshot()
            if self.v.identify(frame)[0] != GRID or self.grid_done(frame):
                self.cache = frame
                return
            cells = self.v.scan_cells(frame)
            targets = [i for i, k in cells.items() if k == "light" and i not in self.blocked]
        total = len(cells)
        if total:
            self.total_houses = total
        if targets:
            t = min(targets)
            log.info("Bang có %d nhà, đánh được %s -> chọn ô %d", total, targets, t)
            self.dialog_tries = 0
            self.pending = t
            self.tap_wait(cell_center(t), frame)
        else:
            log.info("Không còn ô nào đánh được (%d nhà) -> quay lại kiểm tra tiến trình", total)
            self.tap_wait(TAP_BACK, frame)
            time.sleep(self.args.idle_wait)

    def on_dialog(self, frame, scores):
        avail = self.v.deck_available(frame)
        self.dialog_tries += 1
        pending = self.pending
        if self.dialog_tries == 4:
            self.save_unknown(frame)                     # lưu ảnh để xem vì sao chưa vào trận được
        if not any(avail) or self.dialog_tries > 10:
            log.info("Hết deck hoặc không bấm được -> đóng bảng, bỏ qua ô %s", pending)
            if pending:
                self.blocked.add(pending)
            self.tap_wait(TAP_DIALOG_CLOSE, frame, 2.0)
            return
        want = avail.index(True)                         # ưu tiên Deck 1 -> 2 -> 3
        sel = self.v.selected_deck(frame, avail)
        # Chưa chọn đúng deck -> bấm chọn, rồi CHỜ thấy dấu tích mới bấm Tấn công
        # (bấm Tấn công khi game chưa ghi nhận lựa chọn thì không có tác dụng).
        # Nếu 3 lần liên tiếp không thấy dấu tích thì vẫn bấm Tấn công (phòng khi nhận diện dấu tích sai).
        if sel != want and self.dialog_tries < 3:
            log.info("Chọn Deck %d", want + 1)
            if self.args.dry_run:
                time.sleep(0.5)
                return
            self.adb.fire([(DECK_RADIO_X, DECK_ROW_Y[want])])
            t0, ok = time.time(), False
            while time.time() - t0 < 1.5:
                time.sleep(0.1)
                frame = self.adb.screenshot()
                if self.v.selected_deck(frame, avail) == want:
                    ok = True
                    break
            if not ok:
                return                                   # vòng sau thử lại
        log.info("Deck %d đã chọn -> bấm Tấn công", want + 1)
        self.tap_wait(TAP_DIALOG_ATTACK, frame, 5.0)
        self.battle_started = time.time()

    def on_battle(self, frame, scores):
        if self.last_state != BATTLE:
            self.battles += 1
            self.battle_started = time.time()
            log.info("Vào trận #%d", self.battles)
        if self.args.dry_run:
            time.sleep(0.5)
            return
        # Gửi lượt bấm mới khi máy sắp hết việc; vòng sau chụp màn hình CHẠY SONG SONG với việc bấm
        if self.adb.inflight() <= BATTLE_MAX_INFLIGHT:
            self.adb.fire(BATTLE_CARDS * BATTLE_TAP_ROUNDS)
        if self.battle_started and time.time() - self.battle_started > 240 and self.same_count % 40 == 0:
            log.warning("Trận kéo dài hơn 4 phút, vẫn tiếp tục bấm...")

    def on_result(self, frame, scores):
        # Bang chỉ có 1 nhà (0/1) -> ưu tiên Lobby, còn lại bấm Tiếp tục
        if self.total_houses == 1:
            log.info("Bang 0/1 nhà -> bấm Lobby")
            self.tap_wait(TAP_RESULT_LOBBY, frame, 4.0)
        else:
            log.info("Bấm Tiếp tục")
            self.tap_wait(TAP_RESULT_CONTINUE, frame, 4.0)

    def on_unknown(self, frame, scores):
        limit = self.args.unknown_limit
        if self.unknown_count == 1 or self.unknown_count % 10 == 0:
            self.save_unknown(frame)
        if self.unknown_count >= limit:
            raise SystemExit(
                "Gặp màn hình lạ %d lần liên tiếp (popup, mất mạng, văng game...). "
                "Đã dừng để tránh bấm bậy. Xem ảnh trong thư mục debug/." % limit)
        time.sleep(0.4)


# =====================================================================
# CHẠY
# =====================================================================
def benchmark(adb):
    """Đo tốc độ thật trên máy của bạn để biết chỗ nào chậm."""
    adb.check_device()
    print("Đo tốc độ chụp màn hình (mỗi cách 3 lần, lấy giữa):")
    res = adb.calibrate()
    for m, t in sorted(res.items(), key=lambda kv: kv[1]):
        print("  %-4s %5.0f ms" % (m, t * 1000))
    print("=> tool sẽ dùng cách:", adb.method)
    t = time.time()
    adb._run("shell", "input", "keyevent", "0")
    one = (time.time() - t) * 1000
    t = time.time()
    for _ in range(10):
        adb.fire_cmd("input keyevent 0")
    adb.wait_idle(30)
    ses = (time.time() - t) * 1000 / 10
    adb.close()
    print("Mỗi lần bấm, kiểu gọi adb riêng lẻ : %5.0f ms" % one)
    print("Mỗi lần bấm, kiểu phiên giữ mở      : %5.0f ms (đây là cách tool đang dùng)" % ses)
    print("Gửi kết quả này cho người hỗ trợ nếu tool vẫn chậm.")


def detect_file(path):
    img = cv2.imread(path)
    if img is None:
        raise SystemExit("Không đọc được ảnh: " + path)
    v = Vision()
    st, sc = v.identify(img)
    print("Màn hình nhận diện:", st)
    print("Điểm ảnh mẫu:", {k: round(x, 2) for k, x in sc.items()}, "(ngưỡng %.2f)" % MATCH_THRESHOLD)
    print("Thanh tiến trình ô đỏ (màn Bang chiến): %.0f%%" % (v.bar_ratio(img, MAIN_BAR) * 100))
    print("Thanh tiến trình trên lưới 30 ô: %.0f%%" % (v.bar_ratio(img, GRID_BAR) * 100))
    cells = v.scan_cells(img)
    print("Ô đánh được:", [i for i, k in cells.items() if k == "light"])
    print("Ô đang đánh/đã clear:", [i for i, k in cells.items() if k == "dark"])
    print("Deck còn dùng được (1,2,3):", v.deck_available(img))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description="Tool tự động Bang chiến")
    ap.add_argument("--adb", default="adb", help="đường dẫn adb nếu chưa có trong PATH")
    ap.add_argument("--serial", help="mã thiết bị (khi cắm nhiều máy)")
    ap.add_argument("--max-battles", type=int, default=0, help="dừng sau N trận (0 = không giới hạn)")
    ap.add_argument("--idle-wait", type=float, default=IDLE_WAIT, help="giây nghỉ khi chưa có nhà để đánh")
    ap.add_argument("--unknown-limit", type=int, default=60, help="dừng nếu gặp màn hình lạ liên tiếp N lần")
    ap.add_argument("--dry-run", action="store_true", help="chỉ nhận diện và in log, không bấm gì")
    ap.add_argument("--detect", metavar="ANH", help="thử nhận diện trên 1 ảnh chụp màn hình rồi thoát")
    ap.add_argument("--benchmark", action="store_true", help="đo tốc độ chụp/bấm trên máy của bạn rồi thoát")
    ap.add_argument("--timing", action="store_true", help="ghi thêm thời gian chụp màn hình vào log")
    ap.add_argument("--no-calibrate", action="store_true", help="bỏ qua bước tự chọn cách chụp nhanh nhất")
    args = ap.parse_args()

    if args.detect:
        return detect_file(args.detect)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S",
                        handlers=[logging.StreamHandler(),
                                  logging.FileHandler(os.path.join(HERE, "bot.log"), encoding="utf-8")])
    adb = Adb(args.adb, args.serial)
    if args.benchmark:
        return benchmark(adb)
    adb.check_device()
    if not args.no_calibrate:
        res = adb.calibrate()
        log.info("Cách chụp màn hình nhanh nhất: %s (%s)", adb.method,
                 ", ".join("%s %.0fms" % (m, t * 1000) for m, t in res.items()))
    first = adb.screenshot()
    if first.shape[0] > first.shape[1]:
        log.warning("Màn hình đang đứng (dọc). Hãy xoay game sang chế độ ngang rồi chạy lại.")
    bot = Bot(adb, Vision(), args)
    try:
        bot.run()
    except KeyboardInterrupt:
        pass
    finally:
        adb.close()
        log.info("Tổng kết: %d trận, %d lần đổi bang hội mới.", bot.battles, bot.new_guilds)


if __name__ == "__main__":
    main()
