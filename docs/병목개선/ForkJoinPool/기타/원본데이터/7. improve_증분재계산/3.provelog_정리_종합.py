# -*- coding: utf-8 -*-
"""프로브 표 엑셀(_provelog.xlsx) 여러 개의 「정리」 시트를 한 파일로 모으는 스크립트.

2.probelog_to_xlsx.py가 실행 하나마다 만들어 둔 _provelog.xlsx에서
「정리」 시트만 꺼내 실행끼리 나란히 놓고 대조한다. 원본 파일은 열어서 읽기만 한다.

만드는 시트:
  1) 1. 종합   : 실행 목록과, 지표별로 (설정 = 행) × (실행 = 열) 표.
                 값은 「정리」 시트에 있는 것을 그대로 옮기고,
                 ns를 ms로 바꾼 자리만 나눗셈을 한다(열 이름에 단위를 적어 둔다).
  2) 2. 분석   : 종합 시트 값에서 낸 파생값.
                 검산, 실행 사이 재현성, 설정 순위, 코어 사용률, 워커 장부 균등도.
  3) 3번째부터 : 가져온 「정리」 시트 원본을 실행 순서대로 하나씩. 값을 고치지 않는다.

읽는 대상:
  기본은 이 스크립트가 있는 폴더의 *_provelog.xlsx 전부다.
  이름이 ~$로 시작하는 엑셀 임시 파일은 건너뛴다.
  --except 로 준 낱말이 파일 이름에 들어 있으면 건너뛴다(기본값 '말단작업분할개수').
  파일을 인자로 직접 주면 그 파일들만 그 순서대로 읽는다.

실행 순서:
  파일 이름의 날짜와 시각(..._20260813_21_21_provelog.xlsx)으로 정렬한다.
  이름에서 못 읽으면 파일 이름 순으로 둔다.

사용법:
  py -3 "3.provelog_정리_종합.py"
  py -3 "3.provelog_정리_종합.py" -o "결과.xlsx"
  py -3 "3.provelog_정리_종합.py" -d "다른폴더" -o "결과.xlsx"
  py -3 "3.provelog_정리_종합.py" --except "말단작업분할개수" --except "19_36"
  py -3 "3.provelog_정리_종합.py" 파일1.xlsx 파일2.xlsx -o "결과.xlsx"

필요한 패키지: openpyxl (없으면 pip install openpyxl)
"""
import argparse
import re
import sys
from pathlib import Path

try:
    import openpyxl
    from openpyxl.styles import Font, Alignment, PatternFill, Border, Side
    from openpyxl.utils import get_column_letter
except ImportError:
    sys.exit("openpyxl이 필요하다. pip install openpyxl")

# ── 표시 형식 ──────────────────────────────────────────────────────────────
FMT_INT = "#,##0"
FMT_MS = "0.00"
FMT_RATIO = "0.00"
FMT_RATIO3 = "0.000"

HEAD_FILL = PatternFill("solid", fgColor="DDEBF7")     # 표 머리
TITLE_FILL = PatternFill("solid", fgColor="FCE4D6")    # 블록 제목
SEQ_FILL = PatternFill("solid", fgColor="F2F2F2")      # 순차 행
THIN = Side(style="thin", color="BFBFBF")
BOX = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)

# ── 「정리」 시트에서 쓰는 열 이름 ─────────────────────────────────────────
C_IMPL = "구현"
C_PAR = "병렬도(워커 수)"
C_SPLIT = "작업 분할 개수"

# 종합 시트에 만들 지표 블록
#   (블록 제목, 「정리」 시트의 열 이름, 값을 어떻게 옮기나, 표시 형식)
#   'ns→ms'는 1,000,000으로 나눈다는 뜻이고 '그대로'는 나눗셈을 하지 않는다는 뜻이다.
METRICS = [
    ("elapsed — 호출 한 번에 흐른 시간 (ms)", "elapsed(ns)", "ns→ms", FMT_MS),
    ("speedup — 순차 대비 배율 (배)", "speedup(배)", "그대로", FMT_RATIO),
    ("busyCore — 평균 몇 개가 동시에 돌았나 (개)", "busyCore(개)", "그대로", FMT_RATIO),
    ("procCPU — 프로세스 전체 CPU 시간 (ms)", "procCPU(ns)", "ns→ms", FMT_MS),
    ("workerCPU — 워커들이 쓴 CPU 시간 (ms)", "workerCPU(ns)", "ns→ms", FMT_MS),
    ("평가 1회당 워커 CPU (ns)", "평가 1회당 워커 CPU(ns)", "그대로", FMT_INT),
    ("평가 반복문 합 (ms)", "평가 반복문 합(ns)", "ns→ms", FMT_MS),
    ("복사 합 (ms)", "복사 합(ns)", "ns→ms", FMT_MS),
    ("GC (ms)", "GC(ms)", "그대로", FMT_INT),
    ("활동 워커 수 (개)", "활동 워커 수(개)", "그대로", FMT_INT),
    ("워커 장부 최대÷최소 (배)", "최대÷최소(배)", "그대로", FMT_RATIO),
    ("평가 횟수 — 실행마다 같아야 한다 (회)", "평가 횟수(회)", "그대로", FMT_INT),
    ("통과 횟수 — 실행마다 같아야 한다 (회)", "통과 횟수(회)", "그대로", FMT_INT),
]


# ══════════════════════════════════════════════════════════════════════════
# 1. 원본 읽기
# ══════════════════════════════════════════════════════════════════════════
class Run:
    """_provelog.xlsx 하나에서 읽어 온 것."""

    def __init__(self, path):
        self.path = Path(path)
        self.tag = _tag_of(self.path)
        self.sort_key = _sort_key_of(self.path)
        self.header = []        # 「정리」 시트 머리줄 (본 표 부분만)
        self.rows = []          # 「정리」 시트 데이터 행 (본 표 부분만)
        self.raw_grid = []      # 「정리」 시트 전체를 값 그대로 (설명 열 포함)
        self.raw_widths = {}    # 「정리」 시트 열 너비
        self.cond = {}          # 실행 조건 (시각·워밍업·스윕 목록 등)

    # ── 설정 하나를 가리키는 열쇠 ──
    @staticmethod
    def key_of(row, idx):
        impl = row[idx[C_IMPL]]
        if impl == "순차":
            return ("순차", None, None)
        return ("병렬", row[idx[C_PAR]], row[idx[C_SPLIT]])

    def value(self, key, colname):
        idx = self.idx
        if colname not in idx:
            return None
        for row in self.rows:
            if self.key_of(row, idx) == key:
                return row[idx[colname]]
        return None


def _tag_of(path):
    """파일 이름에서 실행 태그를 만든다. 예) ..._20260813_21_21_provelog.xlsx → 0813_2121"""
    m = re.search(r"_(\d{4})(\d{2})(\d{2})_(\d{2})_?(\d{2})_provelog", path.stem)
    if m:
        return "%s%s_%s%s" % (m.group(2), m.group(3), m.group(4), m.group(5))
    return path.stem[-20:]


def _sort_key_of(path):
    m = re.search(r"_(\d{8})_(\d{2})_?(\d{2})_provelog", path.stem)
    if m:
        return (0, m.group(1) + m.group(2) + m.group(3), path.name)
    return (1, "", path.name)


def read_run(path):
    """_provelog.xlsx 하나를 읽는다."""
    run = Run(path)
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)

    if "정리" not in wb.sheetnames:
        wb.close()
        raise ValueError("「정리」 시트가 없다: %s" % path.name)

    ws = wb["정리"]
    grid = [list(r) for r in ws.iter_rows(values_only=True)]
    run.raw_grid = grid

    # 머리줄 찾기 — 첫 칸이 'N(명)'인 줄
    hrow = None
    for i, r in enumerate(grid):
        if r and r[0] == "N(명)":
            hrow = i
            break
    if hrow is None:
        wb.close()
        raise ValueError("「정리」 시트에서 머리줄을 못 찾았다: %s" % path.name)

    # 본 표 부분만 자른다. 오른쪽 설명 열은 빈 칸 하나로 끊겨 있다.
    head = grid[hrow]
    ncol = 0
    for c in head:
        if c is None or c == "":
            break
        ncol += 1
    run.header = [str(c) for c in head[:ncol]]
    run.idx = {name: i for i, name in enumerate(run.header)}

    for r in grid[hrow + 1:]:
        if not r or r[0] is None or r[0] == "":
            continue
        run.rows.append(list(r[:ncol]))

    # 실행 조건 — 「원본 로그」 시트 앞부분에서 뽑는다
    if "원본 로그" in wb.sheetnames:
        run.cond = _read_condition(wb["원본 로그"])

    wb.close()

    # 열 너비는 읽기 전용에서 못 얻으므로 다시 연다(가벼운 작업이 아니라 생략하고 기본값을 쓴다)
    return run


def _read_condition(ws):
    """「원본 로그」 시트 앞부분에서 실행 조건 줄을 뽑는다."""
    cond = {}
    for i, row in enumerate(ws.iter_rows(values_only=True), 1):
        if i > 40:
            break
        line = ""
        for c in row:
            if isinstance(c, str) and len(c) > len(line):
                line = c
        if not line:
            continue
        if "Executing" in line and "시각" not in cond:
            m = re.match(r"\s*(오전|오후)?\s*([\d:]+)", line)
            cond["시각"] = (m.group(0).strip() if m else line[:20])
        if line.startswith("PID="):
            m = re.match(r"PID=(\d+)", line)
            if m:
                cond["PID"] = int(m.group(1))
        if "워밍업" in line and "측정" in line:
            m = re.search(r"워밍업\s*(\d+)\s*·\s*측정\s*(\d+)", line)
            if m:
                cond["워밍업"] = int(m.group(1))
                cond["측정"] = int(m.group(2))
        if line.startswith("스윕 병렬도:"):
            cond["스윕 병렬도"] = line.split(":", 1)[1].strip()
        if line.startswith("스윕 목표 분할 개수:"):
            cond["스윕 분할"] = line.split(":", 1)[1].strip()
        if line.startswith("측정 N:"):
            cond["N"] = line.split(":", 1)[1].strip()
        if "pool.shutdown()" in line:
            cond["풀 닫기"] = "주석" if "현재 주석 상태" in line else line[-20:]
    return cond


# ══════════════════════════════════════════════════════════════════════════
# 2. 종합 시트
# ══════════════════════════════════════════════════════════════════════════
def all_keys(runs):
    """실행 전체에서 나온 설정을 순차 먼저, 그다음 병렬도·분할 순으로 늘어놓는다."""
    keys = []
    for run in runs:
        for row in run.rows:
            k = run.key_of(row, run.idx)
            if k not in keys:
                keys.append(k)
    seq = [k for k in keys if k[0] == "순차"]
    par = sorted([k for k in keys if k[0] == "병렬"],
                 key=lambda k: (k[1] if k[1] is not None else 0,
                                k[2] if k[2] is not None else 0))
    return seq + par


def key_label(key):
    if key[0] == "순차":
        return ("순차", "-", "-")
    return ("병렬", key[1], key[2])


def num(v, how):
    """값을 숫자로 만든다. 못 만들면 원래 값을 그대로 돌려준다."""
    if v is None or v == "":
        return None
    if isinstance(v, str):
        return v                      # '무한(최소가 0)' 같은 표시는 그대로 둔다
    if how == "ns→ms":
        return v / 1e6
    return v


def write_summary(ws, runs, keys):
    tags = [r.tag for r in runs]
    row = 1

    # ── 이 시트가 무엇인지 ──
    ws.cell(row, 1, "이 시트는 _provelog.xlsx 파일들의 「정리」 시트를 한자리에 모은 것이다."
                    " 값은 「정리」 시트 그대로이고, ns를 ms로 바꾼 자리만 1,000,000으로 나눴다.")
    ws.cell(row, 1).font = Font(bold=True)
    row += 2

    # ── 블록 A. 실행 목록 ──
    row = _block_title(ws, row, "A. 가져온 실행 목록  (아래 표들의 열 순서와 같다)")
    head = ["번호", "태그", "실행 시각", "PID", "워밍업", "측정", "N",
            "스윕 병렬도", "스윕 분할 개수", "순차 elapsed(ms)", "설정 수", "파일 이름"]
    _write_head(ws, row, head)
    row += 1
    for i, run in enumerate(runs, 1):
        seqkey = ("순차", None, None)
        seqel = run.value(seqkey, "elapsed(ns)")
        vals = [i, run.tag, run.cond.get("시각", ""), run.cond.get("PID", ""),
                run.cond.get("워밍업", ""), run.cond.get("측정", ""), run.cond.get("N", ""),
                run.cond.get("스윕 병렬도", ""), run.cond.get("스윕 분할", ""),
                (seqel / 1e6 if isinstance(seqel, (int, float)) else ""),
                len(run.rows), run.path.name]
        for c, v in enumerate(vals, 1):
            cell = ws.cell(row, c, v)
            cell.border = BOX
            if c == 10:
                cell.number_format = FMT_MS
        row += 1
    row += 2

    # ── 블록 B 이후. 지표별 표 ──
    letters = "BCDEFGHIJKLMNOPQRSTUVWXYZ"
    for bi, (title, colname, how, fmt) in enumerate(METRICS):
        if not any(colname in r.idx for r in runs):
            continue
        row = _block_title(ws, row, "%s. %s" % (letters[bi], title))
        head = ["구현", "병렬도", "분할"] + tags + ["평균", "최소", "최대", "최대÷최소"]
        _write_head(ws, row, head)
        row += 1
        for key in keys:
            lab = key_label(key)
            for c, v in enumerate(lab, 1):
                cell = ws.cell(row, c, v)
                cell.border = BOX
                if key[0] == "순차":
                    cell.fill = SEQ_FILL
            got = []
            for j, run in enumerate(runs):
                v = num(run.value(key, colname), how)
                cell = ws.cell(row, 4 + j, v)
                cell.border = BOX
                cell.number_format = fmt
                if key[0] == "순차":
                    cell.fill = SEQ_FILL
                if isinstance(v, (int, float)):
                    got.append(v)
            base = 4 + len(runs)
            if got:
                stat = [sum(got) / len(got), min(got), max(got),
                        (max(got) / min(got) if min(got) else None)]
            else:
                stat = [None, None, None, None]
            for c, v in enumerate(stat):
                cell = ws.cell(row, base + c, v)
                cell.border = BOX
                cell.number_format = fmt if c < 3 else FMT_RATIO3
                if key[0] == "순차":
                    cell.fill = SEQ_FILL
            row += 1
        row += 2

    _fit(ws, [8, 8, 8] + [14] * (len(runs) + 4))
    ws.freeze_panes = "D1"


# ══════════════════════════════════════════════════════════════════════════
# 3. 분석 시트
# ══════════════════════════════════════════════════════════════════════════
def write_analysis(ws, runs, keys):
    tags = [r.tag for r in runs]
    parkeys = [k for k in keys if k[0] == "병렬"]
    seqkey = ("순차", None, None)
    row = 1

    ws.cell(row, 1, "이 시트의 값은 전부 「정리」 시트 값에서 계산해 낸 것이다."
                    " 어떻게 냈는지는 각 블록 제목 아래에 적어 두었다.")
    ws.cell(row, 1).font = Font(bold=True)
    row += 2

    # ── 가. 검산 ──
    row = _block_title(ws, row, "가. 검산 — 실행끼리 같아야 하는 값이 같은가")
    row = _note(ws, row, "평가·통과 횟수는 입력이 고정(시드 42)이라 실행과 설정에 상관없이 같아야 한다."
                         " speedup은 순차 elapsed ÷ 병렬 elapsed와 맞아야 한다.")
    _write_head(ws, row, ["검산 항목", "결과", "값"])
    row += 1
    for colname in ("평가 횟수(회)", "통과 횟수(회)"):
        seen = set()
        for run in runs:
            for key in parkeys:
                v = run.value(key, colname)
                if isinstance(v, (int, float)):
                    seen.add(v)
        ok = "같음" if len(seen) == 1 else "다름 — 확인 필요"
        _write_row(ws, row, ["%s 이 실행 전체에서 하나인가" % colname, ok,
                             ", ".join(str(int(x)) for x in sorted(seen))])
        row += 1
    worst = None
    for run in runs:
        se = run.value(seqkey, "elapsed(ns)")
        if not isinstance(se, (int, float)):
            continue
        for key in parkeys:
            el = run.value(key, "elapsed(ns)")
            sp = run.value(key, "speedup(배)")
            if isinstance(el, (int, float)) and isinstance(sp, (int, float)) and el:
                d = abs(se / el - sp)
                if worst is None or d > worst[0]:
                    worst = (d, run.tag, key)
    if worst:
        _write_row(ws, row, ["speedup 재계산과의 최대 차이", "%.4f 배" % worst[0],
                             "%s · 병렬도 %s 분할 %s" % (worst[1], worst[2][1], worst[2][2])])
        row += 1
    row += 2

    # ── 나. 순차 기준선 ──
    row = _block_title(ws, row, "나. 순차 기준선 — 실행마다 순차가 얼마였나")
    row = _note(ws, row, "순차는 실행마다 한 번만 잰다. 이 값이 실행끼리 다르면"
                         " 그 실행의 배율을 다른 실행의 배율과 곧바로 맞대면 안 된다.")
    _write_head(ws, row, ["태그", "순차 elapsed(ms)", "그 실행 최고 배율", "최고 배율 설정"])
    row += 1
    for run in runs:
        se = run.value(seqkey, "elapsed(ns)")
        best = None
        for key in parkeys:
            sp = run.value(key, "speedup(배)")
            if isinstance(sp, (int, float)) and (best is None or sp > best[0]):
                best = (sp, key)
        _write_row(ws, row, [run.tag,
                             se / 1e6 if isinstance(se, (int, float)) else None,
                             best[0] if best else None,
                             "병렬도 %s 분할 %s" % (best[1][1], best[1][2]) if best else ""],
                   fmts={2: FMT_MS, 3: FMT_RATIO})
        row += 1
    row += 2

    # ── 다. 실행 사이 재현성 ──
    row = _block_title(ws, row, "다. 실행 사이 재현성 — 같은 설정이 실행마다 얼마나 갈렸나")
    row = _note(ws, row, "설정 하나의 elapsed를 실행끼리 모아 최대÷최소를 냈다."
                         " 1에 가까울수록 실행을 바꿔도 같은 값이 나온 설정이다. 큰 것부터 늘어놓았다.")
    _write_head(ws, row, ["병렬도", "분할"] + ["%s(ms)" % t for t in tags]
                + ["최소(ms)", "최대(ms)", "최대÷최소"])
    row += 1
    rep = []
    for key in parkeys:
        vals = []
        for run in runs:
            v = run.value(key, "elapsed(ns)")
            vals.append(v / 1e6 if isinstance(v, (int, float)) else None)
        got = [v for v in vals if v is not None]
        ratio = (max(got) / min(got)) if got and min(got) else None
        rep.append((ratio if ratio is not None else -1, key, vals, got))
    for ratio, key, vals, got in sorted(rep, key=lambda x: -x[0]):
        _write_row(ws, row, [key[1], key[2]] + vals
                   + [min(got) if got else None, max(got) if got else None,
                      ratio if ratio > 0 else None],
                   fmts={i: FMT_MS for i in range(3, 3 + len(runs) + 2)} | {3 + len(runs) + 2: FMT_RATIO3})
        row += 1
    row += 2

    # ── 라. 설정 순위 ──
    row = _block_title(ws, row, "라. 설정 순위 — 실행마다 몇 등이었나 (elapsed 짧은 순)")
    row = _note(ws, row, "실행 하나 안에서 병렬 설정끼리만 줄을 세웠다."
                         " 등수가 실행마다 같으면 절대값이 흔들려도 설정 고르기는 믿을 수 있다는 뜻이다.")
    _write_head(ws, row, ["병렬도", "분할"] + ["%s 등" % t for t in tags]
                + ["가장 좋은 등수", "가장 나쁜 등수", "등수 폭"])
    row += 1
    ranks = {}
    for run in runs:
        got = []
        for key in parkeys:
            v = run.value(key, "elapsed(ns)")
            if isinstance(v, (int, float)):
                got.append((v, key))
        for r, (_v, key) in enumerate(sorted(got), 1):
            ranks[(run.tag, key)] = r
    rows_rank = []
    for key in parkeys:
        rs = [ranks.get((t, key)) for t in tags]
        got = [x for x in rs if x]
        rows_rank.append((sum(got) / len(got) if got else 999, key, rs, got))
    for _avg, key, rs, got in sorted(rows_rank):
        _write_row(ws, row, [key[1], key[2]] + rs
                   + [min(got) if got else None, max(got) if got else None,
                      (max(got) - min(got)) if got else None])
        row += 1
    row += 2

    # ── 마. 코어 사용률 ──
    row = _block_title(ws, row, "마. 코어 사용률 — busyCore가 병렬도에 얼마나 닿았나")
    row = _note(ws, row, "busyCore ÷ 병렬도다. 1이면 워커 전원이 내내 돌았다는 뜻이고,"
                         " 작을수록 워커가 놀았다는 뜻이다. 실행별로 낸 값과 그 평균이다.")
    _write_head(ws, row, ["병렬도", "분할"] + tags + ["평균"])
    row += 1
    for key in parkeys:
        vals = []
        for run in runs:
            b = run.value(key, "busyCore(개)")
            vals.append(b / key[1] if isinstance(b, (int, float)) and key[1] else None)
        got = [v for v in vals if v is not None]
        _write_row(ws, row, [key[1], key[2]] + vals + [sum(got) / len(got) if got else None],
                   fmts={i: FMT_RATIO3 for i in range(3, 4 + len(runs))})
        row += 1
    row += 2

    # ── 바. 워커 장부 균등도 ──
    row = _block_title(ws, row, "바. 워커 장부 균등도 — 워커별 CPU의 최대÷최소")
    row = _note(ws, row, "「정리」 시트의 '최대÷최소(배)' 열을 그대로 옮겼다."
                         " 이 값은 본 표와 다른 실행에서 읽은 것이라(설정마다 improve()를 한 번 더 돌린다)"
                         " elapsed와 직접 맞대지 말고 경향만 본다."
                         " '무한'은 CPU를 한 번도 못 받은 워커가 있었다는 뜻이다.")
    _write_head(ws, row, ["병렬도", "분할"] + tags + ["'무한'이 나온 실행 수"])
    row += 1
    for key in parkeys:
        vals, inf = [], 0
        for run in runs:
            v = run.value(key, "최대÷최소(배)")
            if isinstance(v, str) and "무한" in v:
                inf += 1
            vals.append(v)
        _write_row(ws, row, [key[1], key[2]] + vals + [inf],
                   fmts={i: FMT_RATIO for i in range(3, 3 + len(runs))})
        row += 1

    _fit(ws, [9, 8] + [13] * (len(runs) + 4))


# ══════════════════════════════════════════════════════════════════════════
# 4. 원본 「정리」 시트 옮기기
# ══════════════════════════════════════════════════════════════════════════
def write_raw(ws, run):
    ws.cell(1, 1, "아래는 %s 의 「정리」 시트를 값 그대로 옮긴 것이다. 고친 값은 없다."
            % run.path.name).font = Font(bold=True)
    for r, line in enumerate(run.raw_grid, 3):
        for c, v in enumerate(line, 1):
            if v is None:
                continue
            cell = ws.cell(r, c, v)
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                cell.number_format = FMT_INT if abs(v) >= 1000 else FMT_RATIO
    # 머리줄 강조
    for r, line in enumerate(run.raw_grid, 3):
        if line and line[0] == "N(명)":
            for c in range(1, len(line) + 1):
                cell = ws.cell(r, c)
                if cell.value is not None:
                    cell.font = Font(bold=True)
                    cell.fill = HEAD_FILL
            ws.freeze_panes = ws.cell(r + 1, 4)
            break
    _fit(ws, [9, 12, 7, 12, 12] + [16] * 40)


# ══════════════════════════════════════════════════════════════════════════
# 잔손질
# ══════════════════════════════════════════════════════════════════════════
def _block_title(ws, row, text):
    c = ws.cell(row, 1, text)
    c.font = Font(bold=True, size=12)
    c.fill = TITLE_FILL
    return row + 1


def _note(ws, row, text):
    c = ws.cell(row, 1, "· " + text)
    c.font = Font(size=9, color="595959")
    return row + 1


def _write_head(ws, row, names):
    for c, name in enumerate(names, 1):
        cell = ws.cell(row, c, name)
        cell.font = Font(bold=True)
        cell.fill = HEAD_FILL
        cell.border = BOX
        cell.alignment = Alignment(horizontal="center", wrap_text=True)


def _write_row(ws, row, values, fmts=None):
    for c, v in enumerate(values, 1):
        cell = ws.cell(row, c, v)
        cell.border = BOX
        if fmts and c in fmts:
            cell.number_format = fmts[c]


def _fit(ws, widths):
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w


def safe_sheet_name(name):
    for ch in "[]:*?/\\":
        name = name.replace(ch, "_")
    return name[:31]


# ══════════════════════════════════════════════════════════════════════════
# 들머리
# ══════════════════════════════════════════════════════════════════════════
def main():
    ap = argparse.ArgumentParser(description="_provelog.xlsx의 「정리」 시트를 한 파일로 모은다.")
    ap.add_argument("files", nargs="*", help="읽을 _provelog.xlsx 파일들. 없으면 폴더를 훑는다")
    ap.add_argument("-d", "--dir", default=None, help="훑을 폴더 (기본: 이 스크립트가 있는 폴더)")
    ap.add_argument("-o", "--out", default=None, help="만들 엑셀 경로")
    ap.add_argument("--except", dest="skip", action="append", default=None,
                    help="파일 이름에 이 낱말이 들어가면 건너뛴다 (여러 번 줄 수 있다)")
    args = ap.parse_args()

    skips = args.skip if args.skip is not None else ["말단작업분할개수"]

    if args.files:
        paths = [Path(f) for f in args.files]
    else:
        base = Path(args.dir) if args.dir else Path(__file__).resolve().parent
        paths = [p for p in base.glob("*_provelog.xlsx") if not p.name.startswith("~$")]
        paths = [p for p in paths if not any(s in p.name for s in skips)]
        paths.sort(key=_sort_key_of)

    if not paths:
        sys.exit("읽을 _provelog.xlsx가 없다.")

    print("읽을 파일 %d개" % len(paths))
    runs = []
    for p in paths:
        if not p.exists():
            print("  건너뜀 (없음): %s" % p)
            continue
        try:
            run = read_run(p)
        except Exception as e:
            print("  건너뜀 (%s): %s" % (e, p.name))
            continue
        runs.append(run)
        print("  [%s] %s  — 설정 %d개" % (run.tag, p.name, len(run.rows)))
    if not runs:
        sys.exit("읽어 낸 실행이 없다.")

    keys = all_keys(runs)
    print("설정 %d개 (순차 포함)" % len(keys))

    wb = openpyxl.Workbook()
    ws1 = wb.active
    ws1.title = "1. 종합"
    write_summary(ws1, runs, keys)

    ws2 = wb.create_sheet("2. 분석")
    write_analysis(ws2, runs, keys)

    for i, run in enumerate(runs, 3):
        ws = wb.create_sheet(safe_sheet_name("%d. 정리_%s" % (i, run.tag)))
        write_raw(ws, run)

    if args.out:
        out = Path(args.out)
    else:
        base = paths[0].parent
        out = base / "improve_증분재계산_provelog_정리_종합.xlsx"
    wb.save(out)
    print("만든 파일: %s" % out)
    print("시트: %s" % ", ".join(wb.sheetnames))


if __name__ == "__main__":
    main()
