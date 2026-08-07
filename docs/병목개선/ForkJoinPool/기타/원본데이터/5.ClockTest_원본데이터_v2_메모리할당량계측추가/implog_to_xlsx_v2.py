# -*- coding: utf-8 -*-
"""implog_to_xlsx_v2.py — improve() 상세 로그(IMPLOG)를 엑셀로 옮긴다.

v1(1.implog_to_xlsx.py)과 다른 점은 셋이다.
  1) 레코드 형식이 늘어난 것을 읽는다. 말단은 시작시각ms가 붙어 14칸, 스캔은 9칸, 회차별 원값(run)은 16칸이다.
     칸이 짧은 옛 로그(말단 13칸·스캔 8칸)도 그대로 읽힌다.
  2) 판정에 쓰는 배수 두 개를 계산해 넣는다. 분할 1을 기준으로 한 배수와, 직전 분할 대비 배수다.
  3) 회차별 원값(run) 시트를 새로 만든다. 할당 바이트 세 칸이 있는 로그면 할당 열도 함께 채운다.

레코드 형식 (엔진 TeamFormationEngine의 [상세 로그 · 임시] 주석과 같다):
  IMPLOG,leaf,스레드,병렬도,분할,회차,스캔,복사ns,4중루프ns,sumDev횟수,sumDevns,distDev횟수,distDevns,시작시각ms
  IMPLOG,scan,main,병렬도,분할,회차,스캔,바퀴ns,시작시각ms
  IMPLOG,run,구현,병렬도,분할,회차,elapsedNs,procCPUns,mainCPUns,workerCPUns,기타자바ns,자바아닌ns,GCms,main할당B,워커할당B,기타할당B

만드는 시트
  평가 한 번에 걸린 시간 : 분할(동시 실행)이 늘 때 같은 평가 1회가 얼마나 느려지나. 배수 두 열이 여기 있다
  시간이 어디에 쌓였나   : 루프 시간이 sumDev·distDev·나머지 어디에 쌓이나. 복사는 얼마인가
  회차별 원값           : run 레코드 그대로. elapsed·procCPU·CPU 갈래별 값과 할당 바이트
  그래프 시트 4개        : 평가 1회당 루프 시간(ns), 루프 시간 총합(ns, 5회 전체), sumDev 1회당 시간(ns), distDev 1회당 시간(ns)
  leaf 원본 / scan 원본  : 무가공 원본

사용법
  py -3 implog_to_xlsx_v2.py 입력.txt
  py -3 implog_to_xlsx_v2.py 입력.txt -o 출력.xlsx
출력 경로를 생략하면 입력 파일과 같은 자리에 같은 이름의 .xlsx를 만든다.

필요한 패키지: openpyxl (py -3 -m pip install openpyxl)
"""
import argparse
import sys
from pathlib import Path

# ── 원본 시트 컬럼 이름 (레코드의 쉼표 순서 그대로) ──────────
LEAF_HEADER = ["워커 스레드", "병렬도(워커 수)", "분할 개수", "반복 테스트 회차", "4중for문재반복구간번호",
               "teams 복사 시간(ns)", "4중 루프 시간(ns)",
               "sumDev 실행 횟수(회)", "sumDev 시간 합(ns)",
               "distDev 실행 횟수(회)", "distDev 시간 합(ns)", "시작 시각(epoch ms)"]
SCAN_HEADER = ["스레드", "병렬도(워커 수)", "분할 개수", "반복 테스트 회차", "4중for문재반복구간번호",
               "스캔 1바퀴 시간(ns)", "시작 시각(epoch ms)"]
RUN_HEADER = ["구현", "병렬도", "분할", "회차", "elapsed(ms)", "procCPU(ms)", "busyCore(개)",
              "mainCPU(ms)", "workerCPU(ms)", "기타 자바(ms)", "자바 아닌(ms)", "GC(ms)",
              "main 할당(MB)", "워커 할당(MB)", "기타 할당(MB)", "총 할당(MB)"]

SHEET1 = "평가 한 번에 걸린 시간"   # 그래프 시트가 값을 가져오는 시트

NS_FMT = "#,##0"
MS_FMT = "#,##0.0"
PCT_FMT = "0.0"
MUL_FMT = "0.000"
MB_FMT = "#,##0.000"

CHART_WIDTH_CM = 28
CHART_HEIGHT_CM = 14
CHART_ANCHOR = "F4"


def read_lines(path: Path):
    """txt를 줄 목록으로 읽는다. 저장 방식에 따라 인코딩이 다를 수 있어 utf-8, cp949 순서로 시도한다."""
    for enc in ("utf-8", "cp949"):
        try:
            return path.read_text(encoding=enc).splitlines()
        except UnicodeDecodeError:
            continue
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def parse(lines):
    """IMPLOG 줄을 골라 말단·스캔·회차 레코드로 만든다. 형식이 안 맞는 줄은 (줄 번호, 내용)으로 모은다.

    칸 수가 다른 두 형식을 모두 받는다.
      말단 : 13칸(옛 형식) 또는 14칸(시작시각ms 포함)
      스캔 : 8칸(옛 형식) 또는 9칸
      회차 : 13칸(할당 없음) 또는 16칸(할당 세 칸 포함)
    """
    leaf_rows, scan_rows, run_rows, bad = [], [], [], []
    for lineno, raw in enumerate(lines, start=1):
        line = raw.strip()
        if not line.startswith("IMPLOG,"):
            continue
        # 프로브 머리말의 형식 설명 줄은 데이터가 아니므로 조용히 거른다
        if "복사ns" in line or "바퀴ns" in line or "elapsedNs" in line:
            continue
        parts = line.split(",")
        kind = parts[1] if len(parts) > 1 else ""
        try:
            if kind == "leaf" and len(parts) in (13, 14):
                row = [parts[2]] + [int(x) for x in parts[3:]]
                if len(parts) == 13:
                    row.append(None)             # 시작 시각이 없는 옛 로그
                leaf_rows.append(row)
            elif kind == "scan" and len(parts) in (8, 9):
                row = [parts[2]] + [int(x) for x in parts[3:]]
                if len(parts) == 8:
                    row.append(None)
                scan_rows.append(row)
            elif kind == "run" and len(parts) in (13, 16):
                row = [parts[2]] + [int(x) for x in parts[3:]]
                if len(parts) == 13:
                    row += [None, None, None]    # 할당 계측 전의 옛 로그
                run_rows.append(row)
            else:
                bad.append((lineno, line))
        except ValueError:
            bad.append((lineno, line))
    return leaf_rows, scan_rows, run_rows, bad


def write_legend(ws, first_col, entries, header_row=4):
    """데이터 표 오른쪽 빈 공간에 컬럼 설명 표를 쓴다. entries = [(컬럼, 무슨 데이터인가, 어떻게 산출했나), ...]"""
    from openpyxl.utils import get_column_letter
    ws.cell(row=header_row, column=first_col, value="컬럼")
    ws.cell(row=header_row, column=first_col + 1, value="무슨 데이터인가")
    ws.cell(row=header_row, column=first_col + 2, value="어떻게 산출했나")
    for i, (name, what, how) in enumerate(entries, start=header_row + 1):
        ws.cell(row=i, column=first_col, value=name)
        ws.cell(row=i, column=first_col + 1, value=what)
        ws.cell(row=i, column=first_col + 2, value=how)
    for offset, width in ((0, 28), (1, 48), (2, 58)):
        ws.column_dimensions[get_column_letter(first_col + offset)].width = width


def start_question_sheet(wb, title, question, method, headers):
    """질문 시트의 공통 머리 부분: 1행 질문, 2행 계산 방법, 3행 비움, 4행 데이터 머리줄."""
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
    for col, fmt in fmt_by_col.items():
        ws.cell(row=row, column=col).number_format = fmt


def show_axes(chart):
    """차트의 가로축·세로축 눈금 값이 엑셀에서 보이게 한다."""
    for axis, pos in ((chart.x_axis, "b"), (chart.y_axis, "l")):
        axis.delete = False
        axis.tickLblPos = "nextTo"
        axis.majorTickMark = "out"
        axis.axPos = pos


def write_chart_sheet(wb, column_name, source_sheet, number_format, rows, pos, pars, splits):
    """'평가 한 번에 걸린 시간' 시트의 수치 열 하나를 그래프 시트 하나로 만든다."""
    from openpyxl.chart import LineChart, Reference
    from openpyxl.chart.axis import ChartLines
    from openpyxl.chart.label import DataLabelList
    from openpyxl.chart.marker import Marker
    from openpyxl.utils import get_column_letter

    ws = wb.create_sheet(column_name)
    by_key = {(r[0], r[1]): r for r in rows}

    ws.append([f"이 시트의 값: '{source_sheet}' 시트의 {column_name} 열이다. 값을 그대로 옮겼다"])
    ws.append([f"차트: 가로축은 분할 개수(말단 작업 개수)이고, 세로축은 {column_name}이다. "
               f"선은 병렬도 {'과 '.join(str(p) for p in pars)}다"])

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
    chart.add_data(data, titles_from_data=True)
    chart.set_categories(cats)

    show_axes(chart)
    chart.y_axis.majorGridlines = ChartLines()
    chart.legend.position = "b"
    chart.dataLabels = DataLabelList()
    chart.dataLabels.showVal = True
    chart.dataLabels.showSerName = False
    chart.dataLabels.showCatName = False
    chart.dataLabels.showLegendKey = False
    for k, line in enumerate(chart.series):
        line.smooth = False
        line.marker = Marker(symbol="circle", size=6)
        line.dLbls = DataLabelList()
        line.dLbls.showVal = True
        line.dLbls.showSerName = False
        line.dLbls.showCatName = False
        line.dLbls.showLegendKey = False
        line.dLbls.position = "t" if k == 0 else "b"
    ws.add_chart(chart, CHART_ANCHOR)

    ws.column_dimensions["A"].width = 12
    for j in range(len(pars)):
        ws.column_dimensions[get_column_letter(2 + j)].width = 24
    return ws


def build_workbook(leaf_rows, scan_rows, run_rows):
    from openpyxl import Workbook
    wb = Workbook()

    # ── 묶음 합계: (병렬도, 분할) → [말단 수, 평가 합, 통과 합, 복사 합, 루프 합, sumDev 합, distDev 합] ──
    by_setting = {}
    by_setting_run = {}
    runs_seen = set()
    for thread, p, s, run, scan, copy_ns, loop_ns, sum_c, sum_ns, dist_c, dist_ns, _epoch in leaf_rows:
        runs_seen.add(run)
        for key, table in (((p, s), by_setting), ((p, s, run), by_setting_run)):
            a = table.setdefault(key, [0, 0, 0, 0, 0, 0, 0])
            a[0] += 1; a[1] += sum_c; a[2] += dist_c
            a[3] += copy_ns; a[4] += loop_ns; a[5] += sum_ns; a[6] += dist_ns
    run_count = max(len(runs_seen), 1)

    # ── 시트 1: 평가 한 번에 걸린 시간 ────────────────────────────
    # 판정에 쓰는 배수 두 열이 여기 있다. 기준은 같은 병렬도의 가장 작은 분할이다.
    base_per_par = {}          # 병렬도 → 그 병렬도의 최소 분할에서의 평가 1회당 루프 시간
    per_eval = {}              # (병렬도, 분할) → 평가 1회당 루프 시간
    for (p, s) in sorted(by_setting):
        a = by_setting[(p, s)]
        per_eval[(p, s)] = a[4] / a[1]
    for (p, s) in sorted(per_eval):
        if p not in base_per_par:
            base_per_par[p] = per_eval[(p, s)]

    sheet1_rows = []
    ws = start_question_sheet(
        wb, SHEET1,
        "분할(동시 실행)이 늘 때, 같은 평가 1회가 얼마나 느려지나",
        "leaf 원본을 (병렬도 × 분할)로 묶어 측정 회차 전체로 계산했다",
        ["병렬도(워커 수)", "분할 개수", "평가 1회당 루프 시간(ns)",
         "최소 분할 기준 배수", "직전 분할 대비 배수",
         "루프 시간 총합(ns, 측정 회차 전체)", "루프 시간 총합(ms, 호출 1회당)",
         "sumDev 1회당 시간(ns)", "distDev 1회당 시간(ns)",
         "회차별 최소(ns)", "회차별 최대(ns)"])
    prev_by_par = {}
    for (p, s) in sorted(by_setting):
        a = by_setting[(p, s)]
        cur = per_eval[(p, s)]
        step = (cur / prev_by_par[p]) if p in prev_by_par else None
        prev_by_par[p] = cur
        per_run = [by_setting_run[(p, s, r)][4] / by_setting_run[(p, s, r)][1]
                   for r in sorted(runs_seen) if (p, s, r) in by_setting_run]
        row = [p, s, round(cur),
               cur / base_per_par[p], step,
               a[4], round(a[4] / run_count / 1e6, 1),
               round(a[5] / a[1]), round(a[6] / a[2]),
               round(min(per_run)), round(max(per_run))]
        ws.append(row)
        sheet1_rows.append(row)
        set_formats(ws, ws.max_row,
                    {3: NS_FMT, 4: MUL_FMT, 5: MUL_FMT, 6: NS_FMT, 7: MS_FMT,
                     8: NS_FMT, 9: NS_FMT, 10: NS_FMT, 11: NS_FMT})
    write_legend(ws, 13, [
        ("병렬도(워커 수)", "풀에 지정한 워커 스레드 개수", "레코드 식별자 그대로"),
        ("분할 개수", "목표 말단 작업 분할 개수(스윕 값)", "레코드 식별자 그대로"),
        ("평가 1회당 루프 시간(ns)", "평가 1번에 든 평균 시간", "4중 루프 시간 합 ÷ 평가 횟수 합 (측정 회차 전체)"),
        ("최소 분할 기준 배수", "같은 병렬도에서 가장 작은 분할을 1로 놓았을 때의 배수",
         "그 행의 평가 1회당 시간 ÷ 그 병렬도 최소 분할의 평가 1회당 시간"),
        ("직전 분할 대비 배수", "분할을 한 단계 늘릴 때 평가 1회가 몇 배가 되나",
         "그 행의 평가 1회당 시간 ÷ 바로 위 분할의 평가 1회당 시간. 첫 행은 비어 있다"),
        ("두 배수를 쓰는 이유", "판 사이의 절대값은 계측 조건이 달라 비교하지 않는다",
         "각 판 안에서 배수를 내어 배수끼리 맞댄다"),
        ("루프 시간 총합(ns, 측정 회차 전체)", "이 설정의 말단들이 4중 루프에 쓴 시간을 전부 더한 값",
         "leaf 원본의 4중 루프 시간을 이 (병렬도, 분할) 묶음에서 전부 합산"),
        ("루프 시간 총합(ms, 호출 1회당)", "위 총합을 improve() 호출 1번 기준으로 환산한 값",
         "루프 시간 총합 ÷ 측정 회차 수 ÷ 1,000,000"),
        ("sumDev 1회당 시간(ns)", "합 편차 계산 호출 1번의 평균 시간", "sumDev 시간 합 ÷ sumDev 실행 횟수 합"),
        ("distDev 1회당 시간(ns)", "분포 편차 계산 호출 1번의 평균 시간", "distDev 시간 합 ÷ distDev 실행 횟수 합"),
        ("회차별 최소/최대(ns)", "평가 1회당 루프 시간이 회차마다 얼마나 흔들리나", "회차마다 따로 계산한 값의 최소와 최대"),
        ("시간의 뜻", "전부 경과 시간(nanoTime 차이)이고 CPU 시간이 아니다",
         "말단 안에서 잰 값이라, 여러 말단이 동시에 돌면 겹치는 시간이 각각 더해진다"),
        ("평가 1회의 뜻", "가장 안쪽 반복문 몸통이 한 번 실행되는 것",
         "swap, 합 편차 계산, 합 필터 판정, 통과 시 분포 편차 계산, 최선 후보 갱신, 원복 swap까지다"),
        ("절대값 비교 주의", "이 실행은 평가마다 시각 읽기가 추가된 실행이다",
         "부담이 약 1%에서 2%라 절대값을 계측 없는 실행과 직접 비교하지 않는다. 설정 사이의 비교는 유효하다"),
    ])

    # ── 시트 2: 시간이 어디에 쌓였나 ──────────────────────────────
    ws = start_question_sheet(
        wb, "시간이 어디에 쌓였나",
        "4중 루프 시간이 sumDev·distDev·나머지 어디에 쌓이나. 복사는 얼마인가",
        "leaf 원본을 (병렬도 × 분할)로 묶고, 호출 1회 기준은 회차 합을 회차 수로 나눴다",
        ["병렬도(워커 수)", "분할 개수", "호출 1회당 루프 시간(ms)",
         "그중 sumDev 비중(%)", "그중 distDev 비중(%)", "나머지 비중(%)",
         "호출 1회당 복사 시간(ms)"])
    for (p, s) in sorted(by_setting):
        a = by_setting[(p, s)]
        pct_sum = round(100.0 * a[5] / a[4], 1)
        pct_dist = round(100.0 * a[6] / a[4], 1)
        ws.append([p, s, round(a[4] / run_count / 1e6, 1),
                   pct_sum, pct_dist, round(100.0 - pct_sum - pct_dist, 1),
                   round(a[3] / run_count / 1e6, 2)])
        set_formats(ws, ws.max_row, {3: MS_FMT, 4: PCT_FMT, 5: PCT_FMT, 6: PCT_FMT, 7: "#,##0.00"})
    write_legend(ws, 9, [
        ("호출 1회당 루프 시간(ms)", "improve() 호출 1번 동안 모든 말단의 4중 루프 시간 합(평균)", "루프 시간 합 ÷ 측정 회차 수 ÷ 1,000,000"),
        ("그중 sumDev 비중(%)", "루프 시간에서 합 편차 계산이 차지하는 몫", "sumDev 시간 합 ÷ 루프 시간 합 × 100"),
        ("그중 distDev 비중(%)", "루프 시간에서 분포 편차 계산이 차지하는 몫", "distDev 시간 합 ÷ 루프 시간 합 × 100"),
        ("나머지 비중(%)", "swap 2회, 합 필터 판정, best 갱신, 루프 제어, 계측이 쓴 몫", "100 − 위 두 비중"),
        ("호출 1회당 복사 시간(ms)", "호출 1번 동안 teams 복사에 든 시간(평균)", "복사 시간 합 ÷ 측정 회차 수 ÷ 1,000,000"),
        ("세 비중을 더하면", "언제나 100이 된다", "나머지 비중을 반올림한 표시값에서 빼서 만들기 때문이다"),
    ])

    # ── 시트 3: 회차별 원값 (run 레코드가 있을 때만) ────────────────
    if run_rows:
        ws = start_question_sheet(
            wb, "회차별 원값",
            "측정 회차 하나하나의 원값은 얼마인가",
            "IMPLOG run 레코드를 그대로 옮겼다. busyCore와 총 할당은 여기서 계산한다",
            RUN_HEADER)
        for row in run_rows:
            impl, p, s, run, el, proc, main_ns, worker, other, nonjava, gc, ma, wa, oa = row
            vals = [impl,
                    p if impl == "병렬" else "-",
                    s if impl == "병렬" else "-",
                    run,
                    el / 1e6, proc / 1e6,
                    (proc / el) if el else None,
                    main_ns / 1e6, worker / 1e6, other / 1e6, nonjava / 1e6, gc]
            if ma is None:
                vals += [None, None, None, None]
            else:
                vals += [ma / 1e6, wa / 1e6, oa / 1e6, (ma + wa + oa) / 1e6]
            ws.append(vals)
            set_formats(ws, ws.max_row,
                        {5: MS_FMT, 6: MS_FMT, 7: "0.00", 8: MS_FMT, 9: MS_FMT, 10: MS_FMT, 11: MS_FMT,
                         13: MB_FMT, 14: MB_FMT, 15: MB_FMT, 16: MB_FMT})
        write_legend(ws, 18, [
            ("구현", "순차(improveSequential) 또는 병렬(improveBySplitCount)", "레코드 식별자 그대로"),
            ("elapsed(ms)", "improve() 호출 하나가 시작해서 끝날 때까지 흐른 시간", "레코드의 ns를 1,000,000으로 나눴다"),
            ("procCPU(ms)", "그 구간에 프로세스의 모든 스레드가 코어에서 실행된 시간의 합", "레코드의 ns를 1,000,000으로 나눴다"),
            ("busyCore(개)", "그 구간에 평균 몇 개 코어가 동시에 실행됐나", "procCPU ÷ elapsed"),
            ("mainCPU·workerCPU·기타 자바·자바 아닌", "procCPU를 스레드별로 나눈 값", "레코드 그대로"),
            ("할당 세 열(MB)", "그 회차에 main과 워커와 기타 Java 스레드가 새로 만든 객체의 바이트",
             "레코드의 바이트를 1,000,000으로 나눴다. 할당 계측 전의 옛 로그는 빈 칸이다"),
            ("총 할당(MB)", "위 세 값의 합", "main + 워커 + 기타"),
        ])

    # ── 그래프 시트 4개 ──────────────────────────────────────────
    # 각 항목의 숫자는 시트 1 행에서의 위치다. 0=병렬도, 1=분할 개수, 2 이후가 수치 열이다.
    pars = sorted({p for (p, _s) in by_setting})
    splits = sorted({s for (_p, s) in by_setting})
    for column_name, number_format, pos in (
            ("평가 1회당 루프 시간(ns)", NS_FMT, 2),
            ("최소 분할 기준 배수", MUL_FMT, 3),
            ("루프 시간 총합(ns, 측정 회차 전체)", NS_FMT, 5),
            ("sumDev 1회당 시간(ns)", NS_FMT, 7),
            ("distDev 1회당 시간(ns)", NS_FMT, 8)):
        write_chart_sheet(wb, column_name, SHEET1, number_format, sheet1_rows, pos, pars, splits)

    # ── 원본 시트 (값 무가공) ─────────────────────────────────
    ws = wb.create_sheet("leaf 원본")
    ws.append(LEAF_HEADER)
    for row in leaf_rows:
        ws.append(row)
    write_legend(ws, 14, header_row=1, entries=[
        ("워커 스레드", "이 말단 작업을 실행한 워커 스레드 이름", "엔진 말단이 끝날 때 기록한 값 그대로"),
        ("병렬도(워커 수) / 분할 개수 / 반복 테스트 회차", "설정 식별자", "레코드 식별자 그대로"),
        ("4중for문재반복구간번호", "호출 안에서 몇 번째 스캔(while 바퀴)인지(1부터)", "엔진 while이 설정한 식별자"),
        ("teams 복사 시간(ns)", "이 말단이 teams 복사본을 만드는 데 쓴 경과 시간", "복사 블록 앞뒤 nanoTime 차이"),
        ("4중 루프 시간(ns)", "이 말단의 4중 반복문 전체 경과 시간", "루프 앞뒤 nanoTime 차이"),
        ("sumDev 실행 횟수(회)", "합 편차 계산 호출 횟수. 이 말단의 평가 횟수와 같다", "안쪽 루프에서 1씩 센 값"),
        ("sumDev 시간 합(ns)", "합 편차 계산 호출들에 쓴 경과 시간의 합", "호출 앞뒤 nanoTime 차이를 누적"),
        ("distDev 실행 횟수(회)", "분포 편차 계산 호출 횟수. 합 필터 통과 횟수와 같다", "통과 분기에서 1씩 센 값"),
        ("distDev 시간 합(ns)", "분포 편차 계산 호출들에 쓴 경과 시간의 합", "호출 앞뒤 nanoTime 차이를 누적"),
        ("시작 시각(epoch ms)", "이 말단이 시작된 시각", "System.currentTimeMillis. 클럭 CSV의 시각과 맞대는 용도"),
    ])

    ws = wb.create_sheet("scan 원본")
    ws.append(SCAN_HEADER)
    for row in scan_rows:
        ws.append(row)
    write_legend(ws, 9, header_row=1, entries=[
        ("스레드", "스캔을 진행한 스레드. 항상 main", "엔진 while이 기록"),
        ("병렬도(워커 수) / 분할 개수 / 반복 테스트 회차", "leaf 원본과 같은 식별자", "레코드 식별자 그대로"),
        ("4중for문재반복구간번호", "호출 안에서 몇 번째 스캔인지(1부터)", "엔진 while이 바퀴마다 1씩 센 값"),
        ("스캔 1바퀴 시간(ns)", "기준값 계산부터 최선 교환 적용까지 한 바퀴의 경과 시간", "바퀴 앞뒤 nanoTime 차이"),
        ("시작 시각(epoch ms)", "이 스캔이 시작된 시각", "System.currentTimeMillis"),
    ])

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
        import openpyxl  # noqa: F401
    except ImportError:
        sys.exit("openpyxl이 설치되어 있지 않다. 먼저 설치한다: pip install openpyxl")

    leaf_rows, scan_rows, run_rows, bad = parse(read_lines(in_path))
    if not leaf_rows and not scan_rows:
        sys.exit("IMPLOG 레코드가 하나도 없다. 입력 파일이 프로브 콘솔 출력이 맞는지 확인한다.")
    wb = build_workbook(leaf_rows, scan_rows, run_rows)
    try:
        wb.save(out_path)
    except PermissionError:
        sys.exit(f"출력 파일을 쓸 수 없다: {out_path}\n같은 이름의 엑셀 파일이 열려 있으면 닫고 다시 실행한다.")

    print(f"입력: {in_path}")
    print(f"출력: {out_path}")
    print(f"말단(leaf) {len(leaf_rows)}건, 스캔(scan) {len(scan_rows)}건, 회차(run) {len(run_rows)}건을 옮겼다.")
    if not run_rows:
        print("알림: run 레코드가 없어 '회차별 원값' 시트는 만들지 않았다.")
    if bad:
        print(f"IMPLOG로 시작하지만 형식이 맞지 않아 건너뛴 줄 {len(bad)}건:")
        for lineno, line in bad[:10]:
            print(f"  {lineno}행: {line[:100]}")
        if len(bad) > 10:
            print(f"  ... 외 {len(bad) - 10}건")


if __name__ == "__main__":
    main()
