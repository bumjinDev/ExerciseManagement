# -*- coding: utf-8 -*-
"""improve() 상세 로그(IMPLOG) 추출·가공 스크립트.

프로브(ImproveCpuUtilizationProbe) 콘솔 출력을 그대로 저장한 txt에서 IMPLOG 줄만 골라
엑셀(xlsx)로 정리한다. IMPLOG가 아닌 줄(표, [계측] 줄, 워커 장부 등)은 전부 건너뛴다.

레코드 형식 (엔진 TeamFormationEngine의 [상세 로그 · 임시] 주석과 동일):
  IMPLOG,leaf,스레드,병렬도,분할,회차,스캔,복사ns,4중루프ns,sumDev횟수,sumDevns,distDev횟수,distDevns
  IMPLOG,scan,main,병렬도,분할,회차,스캔,바퀴ns

만드는 시트 8개:
  평가 한 번에 걸린 시간 : 분할(동시 실행)이 늘 때 같은 평가 1회가 얼마나 느려지나
  시간이 어디에 쌓였나   : 루프 시간이 sumDev·distDev·나머지 어디에 쌓이나. 복사는 얼마인가
  그래프 시트 4개        : 위 '평가 한 번에 걸린 시간' 시트의 수치 열 하나가 시트 하나다.
                          이름은 그 열 이름을 그대로 쓴다. 평가 1회당 루프 시간(ns),
                          루프 시간 총합(ns, 5회 전체), sumDev 1회당 시간(ns), distDev 1회당 시간(ns).
                          시트마다 차트 1개이고, 가로축은 분할 개수, 세로축은 그 열의 값,
                          선은 병렬도 6과 12 두 개다. 값은 원본 시트의 것을 그대로 옮긴다.
  leaf 원본             : 말단 레코드 무가공 원본
  scan 원본             : 스캔 레코드 무가공 원본

사용법 (경로는 인자로 지정한다):
  python implog_to_xlsx.py 입력.txt
  python implog_to_xlsx.py 입력.txt -o 출력.xlsx
출력 경로를 생략하면 입력 파일과 같은 자리에 같은 이름의 .xlsx를 만든다.

필요한 패키지: openpyxl (없으면 pip install openpyxl)
"""
import argparse
import sys
from pathlib import Path

# ── 원본 시트 컬럼 이름 (레코드의 쉼표 순서 그대로, 이름만 풀어서 표기) ──────────
LEAF_HEADER = ["워커 스레드", "병렬도(워커 수)", "분할 개수", "반복 테스트 회차", "4중for문재반복구간번호",
               "teams 복사 시간(ns)", "4중 루프 시간(ns)",
               "sumDev 실행 횟수(회)", "sumDev 시간 합(ns)",
               "distDev 실행 횟수(회)", "distDev 시간 합(ns)"]
SCAN_HEADER = ["스레드", "병렬도(워커 수)", "분할 개수", "반복 테스트 회차", "4중for문재반복구간번호",
               "스캔 1바퀴 시간(ns)"]

SHEET1 = "평가 한 번에 걸린 시간"   # 그래프 시트가 값을 가져오는 시트

NS_FMT = "#,##0"      # 큰 ns 정수에 천 단위 구분자
MS_FMT = "#,##0.0"    # ms 값
PCT_FMT = "0.0"       # 비중(%)

# ── 그래프 시트 ────────────────────────────────────────────────────────────
# '평가 한 번에 걸린 시간' 시트의 수치 열 하나가 그래프 시트 하나가 된다.
# 시트마다 차트 1개이고, 가로축은 분할 개수, 세로축은 그 열의 값, 선은 병렬도마다 하나다.
# 가로축에는 데이터에 있는 분할 개수를 하나도 빼지 않고 전부 놓는다.
CHART_WIDTH_CM = 28    # 차트 가로 크기(cm)
CHART_HEIGHT_CM = 14   # 차트 세로 크기(cm)
CHART_ANCHOR = "F4"    # 차트를 놓는 자리. 값 표는 C열까지라 겹치지 않는다


def read_lines(path: Path):
    """txt를 줄 목록으로 읽는다. 저장 방식에 따라 인코딩이 다를 수 있어 utf-8, cp949 순서로 시도한다."""
    for enc in ("utf-8", "cp949"):
        try:
            return path.read_text(encoding=enc).splitlines()
        except UnicodeDecodeError:
            continue
    # 둘 다 아니면 깨진 글자만 대체해서 읽는다. IMPLOG 줄 자체는 ASCII라 파싱에는 지장이 없다.
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def parse(lines):
    """IMPLOG 줄을 골라 말단·스캔 레코드 목록으로 만든다. 형식이 안 맞는 줄은 (줄 번호, 내용)으로 모은다."""
    leaf_rows, scan_rows, bad = [], [], []
    for lineno, raw in enumerate(lines, start=1):
        line = raw.strip()
        if not line.startswith("IMPLOG,"):
            continue
        # 프로브 머리말의 형식 설명 줄은 데이터가 아니므로 조용히 거른다
        if "복사ns" in line or "바퀴ns" in line:
            continue
        parts = line.split(",")
        kind = parts[1] if len(parts) > 1 else ""
        try:
            if kind == "leaf" and len(parts) == 13:
                leaf_rows.append([parts[2]] + [int(x) for x in parts[3:]])
            elif kind == "scan" and len(parts) == 8:
                scan_rows.append([parts[2]] + [int(x) for x in parts[3:]])
            else:
                bad.append((lineno, line))
        except ValueError:
            bad.append((lineno, line))
    return leaf_rows, scan_rows, bad


def write_legend(ws, first_col, entries, header_row=4):
    """데이터 표 오른쪽 빈 공간에 컬럼 설명 표를 쓴다. entries = [(컬럼, 무슨 데이터인가, 어떻게 산출했나), ...]
       header_row = 데이터 표의 머리줄 행 번호(질문 시트 4, 원본 시트 1)에 맞춰 나란히 시작한다.
       cell() 직접 쓰기는 append의 행 카운터를 건드리지 않으므로, 모든 append가 끝난 뒤에 부른다."""
    from openpyxl.utils import get_column_letter
    ws.cell(row=header_row, column=first_col, value="컬럼")
    ws.cell(row=header_row, column=first_col + 1, value="무슨 데이터인가")
    ws.cell(row=header_row, column=first_col + 2, value="어떻게 산출했나")
    for i, (name, what, how) in enumerate(entries, start=header_row + 1):
        ws.cell(row=i, column=first_col, value=name)
        ws.cell(row=i, column=first_col + 1, value=what)
        ws.cell(row=i, column=first_col + 2, value=how)
    for offset, width in ((0, 26), (1, 46), (2, 56)):
        ws.column_dimensions[get_column_letter(first_col + offset)].width = width


def start_question_sheet(wb, title, question, method, headers):
    """질문 시트의 공통 머리 부분: 1행 질문, 2행 계산 방법, 3행 비움, 4행 데이터 머리줄.
       append만 써서 행 카운터를 일관되게 유지한다(다음 append가 5행부터 데이터를 쓴다).
       데이터 표의 열 너비는 머리줄 글자 수에 맞춘다(한글은 두 칸으로 셈)."""
    from openpyxl.utils import get_column_letter
    ws = wb.create_sheet(title)
    ws.append(["이 시트가 답하는 질문: " + question])
    ws.append(["계산: " + method])
    ws.append([])
    ws.append(headers)
    for c, h in enumerate(headers, start=1):
        width = sum(2 if ch >= "ᄀ" else 1 for ch in h) + 3
        ws.column_dimensions[get_column_letter(c)].width = width
    return ws


def set_formats(ws, row, fmt_by_col):
    """방금 쓴 행의 지정 컬럼들에 숫자 표시 형식을 건다."""
    for col, fmt in fmt_by_col.items():
        ws.cell(row=row, column=col).number_format = fmt


def show_axes(chart):
    """차트의 가로축·세로축 눈금 값이 엑셀에서 보이게 한다.
       openpyxl은 축을 만들 때 표시 여부(delete)와 눈금 값 자리(tickLblPos)를 파일에 쓰지 않는데,
       엑셀은 그 두 항목이 없으면 축 값을 그리지 않는다. 그래서 여기서 직접 지정한다."""
    for axis, pos in ((chart.x_axis, "b"), (chart.y_axis, "l")):
        axis.delete = False           # 축을 숨기지 않는다
        axis.tickLblPos = "nextTo"    # 눈금 값을 축 옆에 쓴다
        axis.majorTickMark = "out"    # 눈금 표시를 축 바깥쪽에 그린다
        axis.axPos = pos              # 가로축은 아래, 세로축은 왼쪽


def write_chart_sheet(wb, column_name, source_sheet, number_format, rows, pos, pars, splits):
    """'평가 한 번에 걸린 시간' 시트의 수치 열 하나를 그래프 시트 하나로 만든다.

       column_name = 그 열의 이름 그대로. 시트 이름과 세로축 이름으로 쓴다.
       rows = 그 시트에 쓴 행 목록([병렬도, 분할 개수, 값...]). pos = 행에서 이 열의 위치.
       splits = 가로축에 놓을 분할 개수 전체. pars = 선으로 그릴 병렬도 목록.

       시트에는 차트가 읽는 값 표와 차트 하나가 들어간다.
       값은 원본 시트의 것을 그대로 옮기고, 새로 계산한 값은 넣지 않는다."""
    from openpyxl.chart import LineChart, Reference
    from openpyxl.chart.axis import ChartLines
    from openpyxl.chart.label import DataLabelList
    from openpyxl.chart.marker import Marker
    from openpyxl.utils import get_column_letter

    ws = wb.create_sheet(column_name)
    by_key = {(r[0], r[1]): r for r in rows}

    ws.append([f"이 시트의 값: '{source_sheet}' 시트의 {column_name} 열이다. 값을 그대로 옮겼다"])
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
            v = by_key[(p, s)][pos] if (p, s) in by_key else None
            ws.cell(row=r, column=2 + j, value=v).number_format = number_format

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


def build_workbook(leaf_rows, scan_rows):
    from openpyxl import Workbook
    wb = Workbook()

    # ── 묶음 합계: (병렬도, 분할) → [말단 수, 평가 합, 통과 합, 복사 합, 루프 합, sumDev 합, distDev 합] ──
    by_setting = {}
    by_setting_run = {}          # (p, s, run) → 같은 구조
    runs_seen = set()
    for thread, p, s, run, scan, copy_ns, loop_ns, sum_c, sum_ns, dist_c, dist_ns in leaf_rows:
        runs_seen.add(run)
        for key, table in (((p, s), by_setting), ((p, s, run), by_setting_run)):
            a = table.setdefault(key, [0, 0, 0, 0, 0, 0, 0])
            a[0] += 1; a[1] += sum_c; a[2] += dist_c
            a[3] += copy_ns; a[4] += loop_ns; a[5] += sum_ns; a[6] += dist_ns
    run_count = max(len(runs_seen), 1)

    # ── 시트 1: 평가 한 번에 걸린 시간 ────────────────────────────
    sheet1_rows = []   # 아래 그래프 시트가 같은 값을 그대로 쓰도록 행을 모아 둔다
    ws = start_question_sheet(
        wb, SHEET1,
        "분할(동시 실행)이 늘 때, 같은 평가 1회가 얼마나 느려지나",
        "leaf 원본을 (병렬도 × 분할)로 묶어 측정 5회 전체로 계산했다",
        ["병렬도(워커 수)", "분할 개수", "평가 1회당 루프 시간(ns)",
         "루프 시간 총합(ns, 5회 전체)", "루프 시간 총합(ms, 호출 1회당)",
         "sumDev 1회당 시간(ns)", "distDev 1회당 시간(ns)",
         "회차별 최소(ns)", "회차별 최대(ns)"])
    for (p, s) in sorted(by_setting):
        a = by_setting[(p, s)]
        per_run = [by_setting_run[(p, s, r)][4] / by_setting_run[(p, s, r)][1]
                   for r in sorted(runs_seen) if (p, s, r) in by_setting_run]
        row = [p, s, round(a[4] / a[1]),
               a[4], round(a[4] / run_count / 1e6, 1),
               round(a[5] / a[1]), round(a[6] / a[2]),
               round(min(per_run)), round(max(per_run))]
        ws.append(row)
        sheet1_rows.append(row)
        set_formats(ws, ws.max_row,
                    {3: NS_FMT, 4: NS_FMT, 5: MS_FMT, 6: NS_FMT, 7: NS_FMT, 8: NS_FMT, 9: NS_FMT})
    write_legend(ws, 11, [
        ("병렬도(워커 수)", "풀에 지정한 워커 스레드 개수", "레코드 식별자 그대로"),
        ("분할 개수", "목표 말단 작업 분할 개수(스윕 값)", "레코드 식별자 그대로"),
        ("평가 1회당 루프 시간(ns)", "평가 1번에 든 평균 시간", "4중 루프 시간 합 ÷ 평가 횟수 합 (5회 전체)"),
        ("루프 시간 총합(ns, 5회 전체)", "이 설정의 말단들이 4중 루프에 쓴 시간을 전부 더한 값",
         "leaf 원본의 4중 루프 시간을 이 (병렬도, 분할) 묶음에서 전부 합산 (측정 5회 전부 포함)"),
        ("루프 시간 총합(ms, 호출 1회당)", "위 총합을 improve() 호출 1번 기준으로 환산한 값",
         "루프 시간 총합 ÷ 측정 회수(5) ÷ 1,000,000"),
        ("두 총합을 함께 두는 이유", "평균이 같아도 총량이 다를 수 있어 둘 다 필요하다",
         "총합 = 평가 횟수 합(전 설정 4,275,000회로 같음) × 평가 1회당 시간"),
        ("sumDev 1회당 시간(ns)", "sumDeviation 호출 1번의 평균 시간", "sumDev 시간 합 ÷ sumDev 실행 횟수 합"),
        ("distDev 1회당 시간(ns)", "distributionDeviation 호출 1번의 평균 시간", "distDev 시간 합 ÷ distDev 실행 횟수 합"),
        ("회차별 최소/최대(ns)", "평가 1회당 루프 시간이 회차마다 얼마나 흔들리나", "회차(1~5)마다 따로 계산한 값의 최소와 최대"),
        ("시간의 뜻", "전부 경과 시간(nanoTime 차이)이고 CPU 시간이 아니다",
         "말단 안에서 잰 값이라, 여러 말단이 동시에 돌면 겹치는 시간이 각각 더해진다"),
        ("평가 1회의 뜻", "가장 안쪽 반복문 몸통이 한 번 실행되는 것",
         "swap, sumDev, 합 필터 판정, 통과 시 distDev, 최선 후보 갱신, 원복 swap까지다"),
        ("절대값 비교 주의", "이 실행은 평가마다 시각 읽기가 추가된 실행이다",
         "부담이 약 1%에서 2%라 절대값을 이전 스윕 결과와 직접 비교하지 않는다. 설정 사이의 비교는 유효하다"),
    ])

    # ── 시트 2: 시간이 어디에 쌓였나 ──────────────────────────────
    ws = start_question_sheet(
        wb, "시간이 어디에 쌓였나",
        "4중 루프 시간이 sumDev·distDev·나머지 어디에 쌓이나. 복사는 얼마인가",
        "leaf 원본을 (병렬도 × 분할)로 묶고, 호출 1회 기준은 5회 합을 5로 나눴다",
        ["병렬도(워커 수)", "분할 개수", "호출 1회당 루프 시간(ms)",
         "그중 sumDev 비중(%)", "그중 distDev 비중(%)", "나머지 비중(%)",
         "호출 1회당 복사 시간(ms)"])
    for (p, s) in sorted(by_setting):
        a = by_setting[(p, s)]
        # 나머지 비중은 반올림한 표시값에서 뺀다 — 그래야 시트에 보이는 세 값의 합이 정확히 100이 된다
        pct_sum = round(100.0 * a[5] / a[4], 1)
        pct_dist = round(100.0 * a[6] / a[4], 1)
        ws.append([p, s, round(a[4] / run_count / 1e6, 1),
                   pct_sum, pct_dist, round(100.0 - pct_sum - pct_dist, 1),
                   round(a[3] / run_count / 1e6, 2)])
        set_formats(ws, ws.max_row, {3: MS_FMT, 4: PCT_FMT, 5: PCT_FMT, 6: PCT_FMT, 7: "#,##0.00"})
    write_legend(ws, 9, [
        ("호출 1회당 루프 시간(ms)", "improve() 호출 1번 동안 모든 말단의 4중 루프 시간 합(평균)", "루프 시간 합 ÷ 측정 회수 ÷ 1,000,000"),
        ("그중 sumDev 비중(%)", "루프 시간에서 sumDeviation이 차지하는 몫", "sumDev 시간 합 ÷ 루프 시간 합 × 100"),
        ("그중 distDev 비중(%)", "루프 시간에서 distributionDeviation이 차지하는 몫", "distDev 시간 합 ÷ 루프 시간 합 × 100"),
        ("나머지 비중(%)", "swap 2회, 합 필터 판정, best 갱신, 루프 제어, 계측이 쓴 몫", "100 − 위 두 비중"),
        ("호출 1회당 복사 시간(ms)", "호출 1번 동안 teams 복사에 든 시간(평균)", "복사 시간 합 ÷ 측정 회수 ÷ 1,000,000"),
        ("세 비중을 더하면", "언제나 100이 된다",
         "나머지 비중을 반올림한 표시값에서 빼서 만들기 때문이다"),
    ])

    # ── 그래프 시트 4개 ──────────────────────────────────────────
    # 시트 1의 수치 열 하나가 그래프 시트 하나다. 값은 위에서 쓴 것을 그대로 옮긴다.
    # 각 항목의 숫자는 시트 1 행에서의 위치다. 0=병렬도, 1=분할 개수, 2 이후가 수치 열이다.
    pars = sorted({p for (p, _s) in by_setting})
    splits = sorted({s for (_p, s) in by_setting})
    for column_name, number_format, pos in (
            ("평가 1회당 루프 시간(ns)", NS_FMT, 2),
            ("루프 시간 총합(ns, 5회 전체)", NS_FMT, 3),
            ("sumDev 1회당 시간(ns)", NS_FMT, 5),
            ("distDev 1회당 시간(ns)", NS_FMT, 6)):
        write_chart_sheet(wb, column_name, SHEET1, number_format, sheet1_rows, pos, pars, splits)

    # ── 원본 시트 2개 (값 무가공) ─────────────────────────────────
    ws = wb.create_sheet("leaf 원본")
    ws.append(LEAF_HEADER)
    for row in leaf_rows:
        ws.append(row)   # 7만 행이라 셀 단위 표시 형식은 걸지 않는다(값 자체는 정수 그대로)
    write_legend(ws, 13, header_row=1, entries=[
        ("워커 스레드", "이 말단 작업을 실행한 워커 스레드 이름", "엔진 말단이 끝날 때 기록한 값 그대로"),
        ("병렬도(워커 수)", "풀에 지정한 워커 스레드 개수", "레코드 식별자 그대로"),
        ("분할 개수", "목표 말단 작업 분할 개수", "레코드 식별자 그대로"),
        ("반복 테스트 회차", "5회 반복 측정 중 몇 번째 호출인지(1~5)", "프로브가 설정한 식별자"),
        ("4중for문재반복구간번호", "호출 안에서 몇 번째 스캔(while 바퀴)인지(1부터)", "엔진 while이 설정한 식별자"),
        ("teams 복사 시간(ns)", "이 말단이 teams 복사본을 만드는 데 쓴 경과 시간", "복사 블록 앞뒤 nanoTime 차이"),
        ("4중 루프 시간(ns)", "이 말단의 4중 반복문 전체 경과 시간", "루프 앞뒤 nanoTime 차이"),
        ("sumDev 실행 횟수(회)", "sumDeviation 호출 횟수. 이 말단의 평가 횟수와 같다", "안쪽 루프에서 1씩 센 값"),
        ("sumDev 시간 합(ns)", "sumDeviation 호출들에 쓴 경과 시간의 합", "호출 앞뒤 nanoTime 차이를 누적"),
        ("distDev 실행 횟수(회)", "distributionDeviation 호출 횟수. 합 필터 통과 횟수와 같다", "통과 분기에서 1씩 센 값"),
        ("distDev 시간 합(ns)", "distributionDeviation 호출들에 쓴 경과 시간의 합", "호출 앞뒤 nanoTime 차이를 누적"),
    ])

    ws = wb.create_sheet("scan 원본")
    ws.append(SCAN_HEADER)
    for row in scan_rows:
        ws.append(row)   # 원본은 값 그대로. 표시 형식은 걸지 않는다
    write_legend(ws, 8, header_row=1, entries=[
        ("스레드", "스캔을 진행한 스레드. 항상 main", "엔진 while이 기록"),
        ("병렬도(워커 수) / 분할 개수 / 반복 테스트 회차", "leaf 원본과 같은 식별자", "레코드 식별자 그대로"),
        ("4중for문재반복구간번호", "호출 안에서 몇 번째 스캔인지(1부터)", "엔진 while이 바퀴마다 1씩 센 값"),
        ("스캔 1바퀴 시간(ns)", "기준값 계산부터 최선 교환 적용까지 한 바퀴의 경과 시간", "바퀴 앞뒤 nanoTime 차이"),
    ])

    # Workbook()이 자동으로 만드는 빈 기본 시트를 지운다. 모든 시트를 create_sheet로 만들기 때문이다.
    if "Sheet" in wb.sheetnames:
        del wb["Sheet"]

    return wb


def main():
    ap = argparse.ArgumentParser(description="프로브 콘솔 txt에서 IMPLOG 레코드를 추출·가공해 xlsx로 만든다")
    ap.add_argument("input", help="콘솔 출력을 저장한 txt 파일 경로")
    ap.add_argument("-o", "--output", help="출력 xlsx 경로 (생략하면 입력과 같은 이름의 .xlsx)")
    args = ap.parse_args()

    in_path = Path(args.input)
    if not in_path.is_file():
        sys.exit(f"입력 파일이 없다: {in_path}")
    out_path = Path(args.output) if args.output else in_path.with_suffix(".xlsx")

    try:
        import openpyxl  # noqa: F401 — 없으면 여기서 바로 알린다
    except ImportError:
        sys.exit("openpyxl이 설치되어 있지 않다. 먼저 설치한다: pip install openpyxl")

    leaf_rows, scan_rows, bad = parse(read_lines(in_path))
    if not leaf_rows and not scan_rows:
        sys.exit("IMPLOG 레코드가 하나도 없다. 입력 파일이 프로브 콘솔 출력이 맞는지 확인한다.")
    wb = build_workbook(leaf_rows, scan_rows)
    wb.save(out_path)

    print(f"입력: {in_path}")
    print(f"출력: {out_path}")
    print(f"말단(leaf) 레코드 {len(leaf_rows)}건, 스캔(scan) 레코드 {len(scan_rows)}건을 옮겼다.")
    print(f"시트: {SHEET1} · 시간이 어디에 쌓였나 · 그래프 시트 4개 · leaf 원본 · scan 원본")
    print("그래프 시트: 평가 1회당 루프 시간(ns) · 루프 시간 총합(ns, 5회 전체) · "
          "sumDev 1회당 시간(ns) · distDev 1회당 시간(ns)")
    if bad:
        print(f"IMPLOG로 시작하지만 형식이 맞지 않아 건너뛴 줄 {len(bad)}건:")
        for lineno, line in bad[:10]:
            print(f"  {lineno}행: {line[:100]}")
        if len(bad) > 10:
            print(f"  ... 외 {len(bad) - 10}건")


if __name__ == "__main__":
    main()
