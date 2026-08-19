# -*- coding: utf-8 -*-
"""프로브 표 로그 추출 스크립트.

프로브(ImproveCpuUtilizationProbe)가 콘솔에 찍는 본 표와 계측 줄과 워커별 CPU 장부를
그대로 저장한 txt를 읽어 엑셀(xlsx)로 옮긴다.
상세 로그(IMPLOG 줄) 전용인 implog_to_xlsx.py와는 별개 스크립트다. 읽는 대상이 다르다.

만드는 시트는 여덟 개다.
  1) 원본 로그 : txt의 모든 줄을 순서 그대로 한 줄씩 담는다. 값을 고치지 않는다.
  2) 정리      : 설정(병렬도 × 분할) 하나가 한 행이다. 표 값과 워커별 CPU를 한 줄에 모은다.
                 값은 로그에 찍힌 그대로 옮기고 계산해서 만든 값은 넣지 않는다.
  3) 그래프 시트 6개 : 정리 시트의 본 표 열 하나가 시트 하나다. 이름은 그 열 이름을 그대로 쓴다.
                 elapsed(ns), speedup(배), procCPU(ns), busyCore(개), mainCPU(ns), workerCPU(ns)다.
                 시트마다 차트 1개이고, 가로축은 분할 개수, 세로축은 그 열의 값,
                 선은 병렬도마다 하나다. 값은 정리 시트의 것을 그대로 옮긴다.
                 병렬도와 분할이 없는 순차 행은 그래프에 넣지 않는다.

읽는 줄 네 종류:
  1) 본 표 행    : 　 200 | 　  10 | 병렬 | 　  6 | 　    1 |  4126835160 | ... (구분자 | , 14칸)
  2) 계측 줄     :    [계측] 평가 855000회 · 통과 352485회 · 복사 합 ...ns · 평가 반복문 합 ...ns · 평가 1회당 워커 CPU ...ns
  3) 장부 머리줄 :    [N=200 스레드 6 분할 1 워커별 CPU 장부] 활동 워커 1개, CPU 합 4640625000ns
  4) 워커 줄     :       ForkJoinPool-8-worker-1         4640625000 ns  (100.0%)
계측 줄이 없는 로그도 그대로 읽는다. 그 경우 정리 시트에 계측 열을 만들지 않는다.

사용법 (경로는 인자로 지정한다):
  python probelog_to_xlsx.py 입력.txt
  python probelog_to_xlsx.py 입력.txt -o 출력.xlsx
출력 경로를 생략하면 입력 파일과 같은 자리에 같은 이름의 .xlsx를 만든다.

필요한 패키지: openpyxl (없으면 pip install openpyxl)
"""
import argparse
import re
import sys
from pathlib import Path

NS_FMT = "#,##0"
RATIO_FMT = "0.00"     # speedup(배)와 busyCore(개)처럼 소수 두 자리로 보는 값

# ── 그래프 시트 ────────────────────────────────────────────────────────────
# 정리 시트의 본 표 열 하나가 그래프 시트 하나가 된다.
# 시트마다 차트 1개이고, 가로축은 분할 개수, 세로축은 그 열의 값, 선은 병렬도마다 하나다.
# 가로축에는 로그에 있는 분할 개수를 하나도 빼지 않고 전부 놓는다.
# (열 이름, 본 표에서의 자리, 숫자 표시 형식)
CHART_COLUMNS = [
    ("elapsed(ns)", 5, NS_FMT),
    ("speedup(배)", 6, RATIO_FMT),
    ("procCPU(ns)", 7, NS_FMT),
    ("busyCore(개)", 8, RATIO_FMT),
    ("mainCPU(ns)", 9, NS_FMT),
    ("workerCPU(ns)", 10, NS_FMT),
]
CHART_WIDTH_CM = 28    # 차트 가로 크기(cm)
CHART_HEIGHT_CM = 14   # 차트 세로 크기(cm)
CHART_ANCHOR = "F4"    # 차트를 놓는 자리. 값 표는 C열까지라 겹치지 않는다

# 본 표 14칸의 이름 (프로브 머리줄과 같은 순서)
TABLE_HEADER = ["N(명)", "팀당 인원(명)", "구현", "병렬도(워커 수)", "작업 분할 개수",
                "elapsed(ns)", "speedup(배)", "procCPU(ns)", "busyCore(개)", "mainCPU(ns)",
                "workerCPU(ns)", "기타 자바 스레드(ns)", "자바 아닌 스레드(ns)", "GC(ms)"]

GAUGE_HEADER = ["평가 횟수(회)", "통과 횟수(회)", "복사 합(ns)",
                "평가 반복문 합(ns)", "평가 1회당 워커 CPU(ns)"]

LEDGER_HEADER = ["활동 워커 수(개)", "장부 CPU 합(ns)", "최대÷최소(배)"]

RE_GAUGE = re.compile(
    r"\[계측\]\s*평가\s*([\d.]+)회\s*·\s*통과\s*([\d.]+)회\s*·\s*복사 합\s*([\d.]+)ns"
    r"\s*·\s*평가 반복문 합\s*([\d.]+)ns\s*·\s*평가.*?1회당.*?워커 CPU\s*([\d.]+)ns")
RE_LEDGER = re.compile(
    r"\[N=(\d+)\s*스레드\s*(\d+)\s*분할\s*(\d+)\s*워커별 CPU 장부\]"
    r"\s*활동 워커\s*(\d+)개,\s*CPU 합\s*([\d.]+)ns")
RE_WORKER = re.compile(r"^\s+(ForkJoinPool-\S+?)-worker-(\d+)\s+([\d.]+)\s*ns\s*\(\s*([\d.]+)%\)")
RE_RATIO = re.compile(r"최대/최소\s*=\s*([\d.]+|Infinity)배")


def read_lines(path: Path):
    """txt를 줄 목록으로 읽는다. 저장 방식에 따라 인코딩이 다를 수 있어 utf-8, cp949 순서로 시도한다."""
    for enc in ("utf-8", "cp949"):
        try:
            return path.read_text(encoding=enc).splitlines()
        except UnicodeDecodeError:
            continue
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def clean(cell: str) -> str:
    """표 한 칸에서 보통 공백과 전각 공백(U+3000)을 걷어낸다."""
    return cell.replace("　", " ").strip()


def to_num(text: str):
    """표 값을 수로 바꾼다. '-'는 None으로 둔다(순차 행의 병렬도·분할·speedup)."""
    if text in ("-", ""):
        return None
    try:
        return int(text) if re.fullmatch(r"-?\d+", text) else float(text)
    except ValueError:
        return text


def parse(lines):
    """설정 하나를 한 묶음으로 모은다.
       계측 줄과 장부는 바로 앞의 본 표 행에 속하므로, 그 행의 (병렬도, 분할)을 식별자로 붙인다.
       장부 머리줄에는 스레드와 분할이 직접 적혀 있어 그 값을 우선 쓴다."""
    rows = []          # 설정 하나가 하나의 사전
    by_key = {}        # (병렬도, 분할) → 그 사전
    cur = None         # 방금 읽은 본 표 행의 사전
    skipped = []       # 워커가 없다고 프로브가 알린 설정

    for lineno, raw in enumerate(lines, start=1):
        line = raw.rstrip()

        # 본 표 행: | 로 나눠 14칸이고 첫 칸이 숫자
        if "|" in line:
            cells = [clean(c) for c in line.split("|")]
            if len(cells) == len(TABLE_HEADER) and re.fullmatch(r"\d+", cells[0]):
                table = [to_num(c) if i != 2 else c for i, c in enumerate(cells)]
                cur = {"table": table, "gauge": None, "ledger": None, "ratio": None, "workers": {}}
                rows.append(cur)
                by_key[(table[3], table[4])] = cur
                continue

        # 계측 줄
        m = RE_GAUGE.search(line)
        if m and cur is not None:
            cur["gauge"] = [int(round(float(x))) for x in m.groups()]
            continue

        # 장부 머리줄
        m = RE_LEDGER.search(line)
        if m:
            _, par, split, worker_cnt, cpu_total = m.groups()
            target = by_key.get((int(par), int(split)))
            if target is not None:
                cur = target
                cur["ledger"] = [int(worker_cnt), int(float(cpu_total))]
            continue

        # 장부를 못 읽은 설정을 프로브가 알리는 줄
        if "읽을 워커 없음" in line:
            skipped.append(lineno)
            continue

        # 워커 한 명 줄
        m = RE_WORKER.match(line)
        if m and cur is not None:
            cur["workers"][int(m.group(2))] = int(float(m.group(3)))
            continue

        # 최대÷최소 줄
        m = RE_RATIO.search(line)
        if m and cur is not None:
            cur["ratio"] = "무한(최소가 0)" if m.group(1) == "Infinity" else float(m.group(1))
            continue

    return rows, skipped


def show_axes(chart):
    """차트의 가로축·세로축 눈금 값이 엑셀에서 보이게 한다.
       openpyxl은 축을 만들 때 표시 여부(delete)와 눈금 값 자리(tickLblPos)를 파일에 쓰지 않는데,
       엑셀은 그 두 항목이 없으면 축 값을 그리지 않는다. 그래서 여기서 직접 지정한다."""
    for axis, pos in ((chart.x_axis, "b"), (chart.y_axis, "l")):
        axis.delete = False           # 축을 숨기지 않는다
        axis.tickLblPos = "nextTo"    # 눈금 값을 축 옆에 쓴다
        axis.majorTickMark = "out"    # 눈금 표시를 축 바깥쪽에 그린다
        axis.axPos = pos              # 가로축은 아래, 세로축은 왼쪽


def write_chart_sheet(wb, column_name, number_format, values, pars, splits):
    """정리 시트의 본 표 열 하나를 그래프 시트 하나로 만든다.

       column_name = 그 열의 이름 그대로. 시트 이름과 세로축 이름으로 쓴다.
       values = {(병렬도, 분할 개수): 값}. 정리 시트에 쓴 값을 그대로 받는다.
       splits = 가로축에 놓을 분할 개수 전체. pars = 선으로 그릴 병렬도 목록.

       시트에는 차트가 읽는 값 표와 차트 하나가 들어간다.
       값은 정리 시트의 것을 그대로 옮기고, 새로 계산한 값은 넣지 않는다."""
    from openpyxl.chart import LineChart, Reference
    from openpyxl.chart.axis import ChartLines
    from openpyxl.chart.label import DataLabelList
    from openpyxl.chart.marker import Marker
    from openpyxl.utils import get_column_letter

    ws = wb.create_sheet(column_name)
    ws.append([f"이 시트의 값: '정리' 시트의 {column_name} 열이다. 값을 그대로 옮겼다"])
    ws.append([f"차트: 가로축은 분할 개수(말단 작업 개수)이고, 세로축은 {column_name}이다. "
               f"선은 병렬도 {'과 '.join(str(p) for p in pars)} 두 개다"])

    header_row = 3
    first_data_row = 4
    last_data_row = first_data_row + len(splits) - 1

    ws.cell(row=header_row, column=1, value="분할 개수")
    for j, p in enumerate(pars):
        ws.cell(row=header_row, column=2 + j, value=f"병렬도 {p}")
    for i, s in enumerate(splits):
        r = first_data_row + i
        ws.cell(row=r, column=1, value=s)
        for j, p in enumerate(pars):
            ws.cell(row=r, column=2 + j, value=values.get((p, s))).number_format = number_format

    chart = LineChart()
    chart.title = column_name
    chart.y_axis.title = column_name
    chart.x_axis.title = "분할 개수(말단 작업 개수)"
    chart.width = CHART_WIDTH_CM
    chart.height = CHART_HEIGHT_CM

    data = Reference(ws, min_col=2, max_col=1 + len(pars), min_row=header_row, max_row=last_data_row)
    cats = Reference(ws, min_col=1, min_row=first_data_row, max_row=last_data_row)
    chart.add_data(data, titles_from_data=True)   # 머리줄(병렬도 이름)을 선 이름으로 쓴다
    chart.set_categories(cats)                    # 가로축 항목 = 분할 개수

    show_axes(chart)
    chart.y_axis.majorGridlines = ChartLines()    # 가로 눈금선을 넣어 값 읽기를 돕는다
    chart.legend.position = "b"                   # 어느 선이 어느 병렬도인지 아래에 적는다
    chart.dataLabels = DataLabelList()            # 점마다 값을 숫자로 적는다
    chart.dataLabels.showVal = True
    chart.dataLabels.showSerName = False
    chart.dataLabels.showCatName = False
    chart.dataLabels.showLegendKey = False
    for k, line in enumerate(chart.series):
        line.smooth = False                                   # 점 사이를 직선으로 잇는다
        line.marker = Marker(symbol="circle", size=6)         # 값이 있는 자리에 점을 찍는다
        line.dLbls = DataLabelList()
        line.dLbls.showVal = True
        line.dLbls.showSerName = False
        line.dLbls.showCatName = False
        line.dLbls.showLegendKey = False
        line.dLbls.position = "t" if k == 0 else "b"          # 선마다 값 자리를 위아래로 나눠 글자가 겹치지 않게 한다
    ws.add_chart(chart, CHART_ANCHOR)

    ws.column_dimensions["A"].width = 12
    for j in range(len(pars)):
        ws.column_dimensions[get_column_letter(2 + j)].width = 24
    return ws


def build_workbook(lines, rows):
    from openpyxl import Workbook
    from openpyxl.utils import get_column_letter
    wb = Workbook()

    # ── 시트 1: 원본 로그 ─────────────────────────────────────────
    ws = wb.active
    ws.title = "원본 로그"
    ws.append(["줄 번호", "원본 줄"])
    for i, line in enumerate(lines, start=1):
        ws.append([i, line])
    ws.column_dimensions["A"].width = 9
    ws.column_dimensions["B"].width = 200

    # ── 시트 2: 정리 ──────────────────────────────────────────────
    has_gauge = any(r["gauge"] for r in rows)
    worker_nums = sorted({n for r in rows for n in r["workers"]})

    header = list(TABLE_HEADER)
    if has_gauge:
        header += GAUGE_HEADER
    header += LEDGER_HEADER
    header += [f"워커{n} CPU(ns)" for n in worker_nums]

    ws = wb.create_sheet("정리")
    ws.append(["이 시트는 원본 로그에서 값을 뽑아 설정 하나를 한 행으로 모은 것이다. 계산해서 만든 값은 넣지 않았다."])
    ws.append([])
    ws.append(header)
    for c, h in enumerate(header, start=1):
        ws.column_dimensions[get_column_letter(c)].width = sum(2 if ch >= "ᄀ" else 1 for ch in h) + 3

    for r in rows:
        row = list(r["table"])
        if has_gauge:
            row += r["gauge"] if r["gauge"] else [None] * len(GAUGE_HEADER)
        row += (r["ledger"] if r["ledger"] else [None, None]) + [r["ratio"]]
        row += [r["workers"].get(n) for n in worker_nums]
        ws.append(row)
        for c in range(1, len(header) + 1):
            if isinstance(ws.cell(row=ws.max_row, column=c).value, int):
                ws.cell(row=ws.max_row, column=c).number_format = NS_FMT

    # 컬럼 설명 표를 데이터 표 오른쪽 빈 공간에 붙인다
    legend = [
        ("N(명) / 팀당 인원(명)", "측정에 쓴 인원 수와 팀 정원", "본 표 값 그대로"),
        ("구현", "순차(improveSequential)인지 병렬(improve)인지", "본 표 값 그대로"),
        ("병렬도(워커 수)", "풀에 지정한 워커 스레드 개수", "본 표 값 그대로. 순차 행은 비어 있다"),
        ("작업 분할 개수", "이 설정에서 실제로 만들어진 말단 작업 수", "본 표 값 그대로"),
        ("elapsed(ns)", "improve() 호출 한 번에 실제로 흐른 시간", "본 표 값 그대로. 측정 5회 평균이다"),
        ("speedup(배)", "순차보다 몇 배 빨라졌나", "본 표 값 그대로. 프로브가 순차 elapsed를 병렬 elapsed로 나눠 계산했다"),
        ("procCPU(ns)", "프로세스의 모든 스레드가 코어에서 실행된 시간의 합", "본 표 값 그대로"),
        ("busyCore(개)", "그 구간에 평균 몇 개가 동시에 실행됐나", "본 표 값 그대로. 프로브가 procCPU를 elapsed로 나눠 계산했다"),
        ("mainCPU(ns)", "main 스레드가 쓴 CPU 시간", "본 표 값 그대로. Windows는 15.625ms 단위로만 갱신해 작은 값이 0으로 찍힌다"),
        ("workerCPU(ns)", "ForkJoinPool 워커들이 쓴 CPU 시간의 합", "본 표 값 그대로"),
        ("기타 자바 스레드(ns)", "main도 워커도 아닌 Java 스레드가 쓴 CPU 시간", "본 표 값 그대로"),
        ("자바 아닌 스레드(ns)", "procCPU에서 Java 스레드 CPU 합을 뺀 나머지",
         "본 표 값 그대로. GC 작업 스레드와 JIT 컴파일러 스레드와 VM Thread 등이 들어간다"),
        ("GC(ms)", "GC 수집 시간 증가분", "본 표 값 그대로. 실제 흐른 시간 기준이고 CPU 시간이 아니다"),
    ]
    if has_gauge:
        legend += [
            ("평가 횟수(회)", "가장 안쪽 반복문 몸통이 실행된 총횟수", "계측 줄 값 그대로"),
            ("통과 횟수(회)", "합 필터를 통과해 분포 편차 계산까지 간 횟수", "계측 줄 값 그대로"),
            ("복사 합(ns)", "말단들이 teams 복사본을 만드는 데 쓴 시간의 합", "계측 줄 값 그대로"),
            ("평가 반복문 합(ns)", "말단들의 4중 루프 시간의 합",
             "계측 줄 값 그대로. 여러 말단이 동시에 돌면 겹치는 시간이 각각 더해진다"),
            ("평가 1회당 워커 CPU(ns)", "같은 평가 한 번에 든 워커 CPU 시간",
             "계측 줄 값 그대로. 프로브가 workerCPU를 평가 횟수로 나눠 계산했다"),
        ]
    legend += [
        ("활동 워커 수(개)", "장부에 이름이 찍힌 워커 수", "장부 머리줄 값 그대로"),
        ("장부 CPU 합(ns)", "그 워커들의 CPU 시간을 전부 더한 값", "장부 머리줄 값 그대로"),
        ("최대÷최소(배)", "일이 몰렸는지 나타내는 비. 1에 가까울수록 균등",
         "장부 마지막 줄 값 그대로. 최소가 0이면 프로브가 Infinity로 찍으므로 표시로 바꿔 담았다"),
        ("워커N CPU(ns)", "그 설정에서 워커 N번이 쓴 CPU 시간", "워커 줄 값 그대로. 이름 끝 번호를 열 번호로 썼다"),
        ("워커 관련 열의 출처", "본 표와 다른 실행이다",
         "설정마다 improve()를 한 번 더 돌려 그 호출의 워커별 CPU를 읽은 값이다"),
        ("빈 칸", "그 설정에 해당 값이 없다는 뜻이다",
         "순차 행에는 병렬도와 분할과 워커가 없고, 워커 열은 그 설정에서 일을 받지 못한 번호가 비어 있다"),
    ]
    first = len(header) + 2
    ws.cell(row=3, column=first, value="컬럼")
    ws.cell(row=3, column=first + 1, value="무슨 데이터인가")
    ws.cell(row=3, column=first + 2, value="어떻게 산출했나")
    for i, (name, what, how) in enumerate(legend, start=4):
        ws.cell(row=i, column=first, value=name)
        ws.cell(row=i, column=first + 1, value=what)
        ws.cell(row=i, column=first + 2, value=how)
    for offset, width in ((0, 26), (1, 46), (2, 62)):
        ws.column_dimensions[get_column_letter(first + offset)].width = width

    # ── 그래프 시트 6개 ──────────────────────────────────────────
    # 정리 시트의 본 표 열 하나가 그래프 시트 하나다. 값은 위에서 쓴 것을 그대로 옮긴다.
    # 병렬도와 분할이 있는 병렬 행만 쓴다. 순차 행은 그 두 값이 없어 가로축에 놓을 자리가 없다.
    par_tables = [r["table"] for r in rows if r["table"][3] is not None and r["table"][4] is not None]
    if par_tables:
        pars = sorted({t[3] for t in par_tables})
        splits = sorted({t[4] for t in par_tables})
        for column_name, pos, number_format in CHART_COLUMNS:
            values = {(t[3], t[4]): t[pos] for t in par_tables}
            write_chart_sheet(wb, column_name, number_format, values, pars, splits)

    return wb


def main():
    ap = argparse.ArgumentParser(description="프로브 표 로그를 원본 시트와 정리 시트 두 개로 옮긴다")
    ap.add_argument("input", help="콘솔 출력을 저장한 txt 파일 경로")
    ap.add_argument("-o", "--output", help="출력 xlsx 경로 (생략하면 입력과 같은 이름의 .xlsx)")
    args = ap.parse_args()

    in_path = Path(args.input)
    if not in_path.is_file():
        sys.exit(f"입력 파일이 없다: {in_path}")
    out_path = Path(args.output) if args.output else in_path.with_suffix(".xlsx")

    try:
        import openpyxl  # noqa: F401
    except ImportError:
        sys.exit("openpyxl이 설치되어 있지 않다. 먼저 설치한다: pip install openpyxl")

    lines = read_lines(in_path)
    rows, skipped = parse(lines)
    if not rows:
        sys.exit("본 표 행을 하나도 찾지 못했다. 입력 파일이 프로브 콘솔 출력이 맞는지 확인한다.")

    wb = build_workbook(lines, rows)
    wb.save(out_path)

    par = sum(1 for r in rows if r["table"][2] == "병렬")
    print(f"입력: {in_path}")
    print(f"출력: {out_path}")
    print(f"원본 로그 시트: {len(lines)}줄")
    print(f"정리 시트: {len(rows)}행 (순차 {len(rows) - par}행, 병렬 {par}행)")
    print("그래프 시트: " + " · ".join(name for name, _pos, _fmt in CHART_COLUMNS))
    print(f"  계측 줄이 붙은 행 {sum(1 for r in rows if r['gauge'])}개 · "
          f"워커 장부가 붙은 행 {sum(1 for r in rows if r['ledger'])}개 · "
          f"워커 값 {sum(len(r['workers']) for r in rows)}건")
    if not any(r["gauge"] for r in rows):
        print("  계측 줄이 없는 로그다. 정리 시트에 계측 열을 만들지 않았다.")
    if skipped:
        print(f"  주의: 프로브가 '읽을 워커 없음'을 찍은 줄이 {len(skipped)}개 있다(줄 번호 {skipped[:5]})")
    bad = [(r["table"][3], r["table"][4]) for r in rows
           if r["ledger"] and r["ledger"][0] != len(r["workers"])]
    if bad:
        print(f"  주의: 장부 머리줄의 활동 워커 수와 실제 워커 줄 수가 다른 설정 {bad}")


if __name__ == "__main__":
    main()
