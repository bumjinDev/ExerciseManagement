# clocklog_to_xlsx_v2.py — 클럭 CSV(typeperf)와 프로브 콘솔 로그를 묶어 클럭 정리 엑셀을 만든다.
#
# v1과 다른 점: 프로브가 IMPLOG run 레코드 끝에 붙이기 시작한 할당 바이트 세 칸을 읽어
#   '회차별 측정값' 시트에 할당 열 여섯 개를 만든다. 그 세 칸이 없는 옛 로그도 그대로 읽히며 할당 열만 빈 칸이 된다.
#   나머지 시트와 계산은 v1과 같다.
#
# 입력 두 개:
#   1) typeperf가 남긴 클럭 CSV (% Processor Performance, 논리 프로세서별)
#   2) 프로브 콘솔 로그 (IntelliJ 콘솔 전체 복사본). [구간 시각] 줄과 IMPLOG 줄을 읽는다.
# 출력 시트:
#   클럭 정리          : 1초 간격 표본을 논리 프로세서 숫자순으로 정리. GHz 열은 기본 클럭 칸(B2)을 참조하는 수식.
#   구간과 스레드 매핑  : 구간(순차/워밍업/측정 회차/워커 CPU 읽기)마다 시각 경계·실행 스레드·평균 클럭(AVERAGEIFS 수식).
#   스레드별 클럭       : 측정 회차 안의 스레드별 활동 창과 클럭. main 행의 활동 시간 합은 IMPLOG run 레코드의 mainCPU다.
#   회차별 측정값       : 측정 5회 각각의 원값(elapsed·procCPU·mainCPU 등)과 할당 바이트. IMPLOG run 레코드가 있을 때만 만든다.
#   차트               : 전체 평균 GHz 추이(선), 구간 전체 평균(막대), 회차별 스레드 클럭(선).
# 구간 경계의 출처:
#   전체·워밍업·워커 읽기 = [구간 시각] 출력. 측정 회차 = IMPLOG scan 레코드의 시작시각ms(epoch ms).
#   날짜는 CSV의 시각 열에서 가져온다(콘솔 로그의 [구간 시각]에는 시각만 있다). 자정을 넘긴 실행은 지원하지 않는다.
# 필요한 패키지: openpyxl (py -3 -m pip install openpyxl)

import argparse
import csv
import datetime
import re
import sys

from openpyxl import Workbook
from openpyxl.chart import BarChart, LineChart, Reference
from openpyxl.styles import Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

AR = 'Arial'


def utc_naive(ms):
    """epoch ms → UTC 기준 naive datetime. (utcfromtimestamp는 파이썬 3.12부터 제거 예정이라 쓰지 않는다)"""
    return datetime.datetime.fromtimestamp(ms / 1000.0, tz=datetime.timezone.utc).replace(tzinfo=None)


def read_text(path):
    for enc in ('utf-8-sig', 'utf-8', 'cp949'):
        try:
            with open(path, encoding=enc) as f:
                return f.read().splitlines()
        except UnicodeDecodeError:
            continue
    raise SystemExit(f'인코딩을 알 수 없다: {path}')


def parse_clock_csv(path):
    """(측정 날짜, [(시각, [프로세서별 %], 전체 %)]) 반환. 첫 표본(빈 값)은 버린다."""
    rows = []
    with open(path, encoding='utf-8-sig', newline='') as f:
        r = csv.reader(f)
        header = next(r)
        proc_idx = {}     # 논리 프로세서 번호 → 열 위치
        total_idx = None
        for i, h in enumerate(header[1:]):
            m = re.search(r'\((\d+),(\d+)\)', h)
            if m:
                proc_idx[int(m.group(2))] = i
            elif '(_Total)' in h:
                total_idx = i
        if not proc_idx or total_idx is None:
            raise SystemExit('CSV 머리줄에서 프로세서 열을 찾지 못했다')
        procs = sorted(proc_idx)
        for line in r:
            if len(line) < 2 or line[1].strip() == '':
                continue                      # 첫 표본: 직전 값이 없어 빈 칸
            t = datetime.datetime.strptime(line[0], '%m/%d/%Y %H:%M:%S.%f')
            vals = line[1:]
            rows.append((t, [float(vals[proc_idx[p]]) for p in procs], float(vals[total_idx])))
    if not rows:
        raise SystemExit('CSV에 값 있는 표본이 없다')
    return procs, rows


def parse_console_log(lines, date):
    """[구간 시각] 표시, IMPLOG scan/leaf/run, 워커별 CPU 장부의 워커 이름을 모은다."""
    marks = []            # (시각, '시작'|'끝', 라벨)
    scans, leaves = [], []
    runs = []             # IMPLOG run 레코드(측정 회차별 원값). run 레코드가 없는 옛 로그면 빈 목록
    ledger = {}           # (병렬도, 분할) → [워커 이름]
    cur_ledger = None
    for line in lines:
        m = re.match(r'\[구간 시각 · 임시\]\s+(\d+):(\d+):(\d+)\.(\d+)\s+(시작|끝)\s+·\s+(.+)', line)
        if m:
            us = int((m.group(4) + '0' * 6)[:6])
            t = datetime.datetime.combine(
                date, datetime.time(int(m.group(1)), int(m.group(2)), int(m.group(3)), us))
            label = re.sub(r'\s*\(N=\d+\)\s*$', '', m.group(6)).strip()
            marks.append((t, m.group(5), label))
            continue
        if line.startswith('IMPLOG,scan,'):
            p = line.split(',')
            scans.append(dict(par=int(p[3]), split=int(p[4]), run=int(p[5]), scan=int(p[6]),
                              loop_ns=int(p[7]), epoch_ms=int(p[8])))
            continue
        if line.startswith('IMPLOG,leaf,'):
            p = line.split(',')
            leaves.append(dict(thread=p[2], par=int(p[3]), split=int(p[4]), run=int(p[5]),
                               scan=int(p[6]), loop_ns=int(p[8]),
                               eval_count=int(p[9]), pass_count=int(p[11]), epoch_ms=int(p[13])))
            continue
        if line.startswith('IMPLOG,run,'):
            # 측정 회차별 원값: 구현,병렬도,분할,회차,elapsedNs,procCPUns,mainCPUns,workerCPUns,기타자바ns,자바아닌ns,GCms
            #                 (할당 계측을 넣은 뒤부터) main할당B,워커할당B,기타할당B가 뒤에 더 붙는다.
            #                 그 세 칸이 없는 옛 로그는 None으로 두어 할당 열만 비운다.
            p = line.split(',')
            has_alloc = len(p) >= 16
            runs.append(dict(impl=p[2], par=int(p[3]), split=int(p[4]), run=int(p[5]),
                             elapsed_ns=int(p[6]), proc_ns=int(p[7]), main_ns=int(p[8]),
                             worker_ns=int(p[9]), other_ns=int(p[10]), nonjava_ns=int(p[11]),
                             gc_ms=int(p[12]),
                             main_alloc_b=int(p[13]) if has_alloc else None,
                             worker_alloc_b=int(p[14]) if has_alloc else None,
                             other_alloc_b=int(p[15]) if has_alloc else None))
            continue
        m = re.search(r'\[N=\d+ 스레드 (\d+) 분할 (\d+) 워커별 CPU 장부\]', line)
        if m:
            cur_ledger = (int(m.group(1)), int(m.group(2)))
            ledger[cur_ledger] = []
            continue
        if cur_ledger is not None:
            m = re.match(r'\s+(ForkJoinPool\S+)\s', line)
            if m:
                ledger[cur_ledger].append(m.group(1))
            elif '최대/최소' in line:
                cur_ledger = None
    return marks, scans, leaves, ledger, runs


def epoch_offset(marks, scans):
    """epoch ms(UTC 기준)를 [구간 시각]의 지역 시각으로 옮기는 편차를 로그에서 직접 구한다.
    분석하는 기계의 시간대를 쓰지 않는다. 측정 기계와 다르면 구간이 통째로 어긋나기 때문이다.
    방법: 첫 병렬 설정의 첫 스캔 epoch는 그 설정의 시작 표시 직후(워밍업 몇 초 뒤)이므로,
    (시작 표시 − epoch의 UTC 시각)을 15분 배수로 반올림한 값이 시간대 편차다."""
    for t, k, l in marks:
        m = re.match(r'병렬도 (\d+) 분할 (\d+)$', l)
        if m and k == '시작':
            par, sp = int(m.group(1)), int(m.group(2))
            cfg = [s for s in scans if s['par'] == par and s['split'] == sp]
            if not cfg:
                continue
            first = min(cfg, key=lambda x: (x['run'], x['scan']))
            utc = utc_naive(first['epoch_ms'])
            delta_s = (t - utc).total_seconds()
            quarter = 15 * 60
            return datetime.timedelta(seconds=round(delta_s / quarter) * quarter)
    return datetime.timedelta(0)


def build_phases(marks, scans, leaves, ledger, offset):
    """구간 목록 [(이름, 시작, 끝, 스레드 설명, 전체 구간 여부)]를 만든다."""
    def epoch_to_local(ms):
        return utc_naive(ms) + offset

    def find(label, kind):
        for t, k, l in marks:
            if k == kind and l == label:
                return t
        return None

    phases = []
    seq_st, seq_en = find('순차', '시작'), find('순차', '끝')
    if seq_st and seq_en:
        phases.append(('순차 전체', seq_st, seq_en,
                       'main (improveSequential, 워밍업+측정)', True))

    configs = []          # 등장 순서대로 (병렬도, 분할)
    for t, k, l in marks:
        m = re.match(r'병렬도 (\d+) 분할 (\d+)$', l)
        if m and k == '시작':
            configs.append((int(m.group(1)), int(m.group(2))))

    for par, sp in configs:
        lab = f'병렬도 {par} 분할 {sp}'
        st, en = find(lab, '시작'), find(lab, '끝')
        if st is None or en is None:
            continue
        phases.append((f'{lab} 전체', st, en, '워커(호출마다 새 풀)+main', True))
        cfg_scans = [s for s in scans if s['par'] == par and s['split'] == sp]
        if not cfg_scans:
            continue
        runs = sorted(set(s['run'] for s in cfg_scans))
        bounds = {}
        for rn in runs:
            rs = [s for s in cfg_scans if s['run'] == rn]
            first = min(rs, key=lambda x: x['scan'])
            last = max(rs, key=lambda x: x['scan'])
            bounds[rn] = (epoch_to_local(first['epoch_ms']),
                          epoch_to_local(last['epoch_ms'])
                          + datetime.timedelta(microseconds=last['loop_ns'] / 1000))
        phases.append((f'{lab} 워밍업', st, bounds[runs[0]][0], '워커 (IMPLOG 기록 없음)', False))
        for rn in runs:
            thr = sorted(set(l['thread'] for l in leaves
                             if l['par'] == par and l['split'] == sp and l['run'] == rn))
            phases.append((f'{lab} 측정 {rn}회차', bounds[rn][0], bounds[rn][1],
                           ', '.join(thr) + ' + main(스캔 사이 순차 구간)', False))
        led = ', '.join(ledger.get((par, sp), [])) or '워커'
        phases.append((f'{lab} 워커별 CPU 읽기 실행', bounds[runs[-1]][1], en, led + ' + main', False))
    return phases


def build_thread_and_pool_rows(marks, scans, leaves, offset, clock, runs):
    """새 시트용 자료 두 벌을 만든다.
    thread_rows : 행 하나 = (측정 회차, 스레드). 활동 창은 IMPLOG에서 계산한다.
                  워커의 활동 시간 합 = 그 워커의 4중 루프 경과 시간 합.
                  main의 활동 시간 합 = 그 회차의 mainCPU(IMPLOG run 레코드, CPU 시간).
                  이전 방식(스캔 바퀴 합 − 말단 루프 합)은 말단이 2개 이상 겹치면 겹친 시간이 두 번 빠져
                  음수가 나오므로 쓰지 않는다(2026-08-04). run 레코드가 없는 옛 로그는 None으로 비운다.
    pool_rows   : 행 하나 = (풀, 겹치는 클럭 표본 1개). 풀의 활동 창과 그 창에 걸린 표본의 위치를 담는다."""
    def conv(ms):
        return utc_naive(ms) + offset

    configs = []
    for t, k, l in marks:
        m = re.match(r'병렬도 (\d+) 분할 (\d+)$', l)
        if m and k == '시작':
            configs.append((int(m.group(1)), int(m.group(2))))

    thread_rows, pool_rows = [], []
    for par, sp in configs:
        lab = f'병렬도 {par} 분할 {sp}'
        cfg_scans = [s for s in scans if s['par'] == par and s['split'] == sp]
        cfg_leaves = [l for l in leaves if l['par'] == par and l['split'] == sp]
        for rn in sorted(set(s['run'] for s in cfg_scans)):
            run_scans = [s for s in cfg_scans if s['run'] == rn]
            run_leaves = [l for l in cfg_leaves if l['run'] == rn]
            # 워커 행
            for thr in sorted(set(l['thread'] for l in run_leaves)):
                tl = [l for l in run_leaves if l['thread'] == thr]
                st = min(conv(l['epoch_ms']) for l in tl)
                en = max(conv(l['epoch_ms'])
                         + datetime.timedelta(microseconds=l['loop_ns'] / 1000) for l in tl)
                thread_rows.append(dict(config=lab, run=rn, thread=thr, role='워커',
                                        start=st, end=en, units=len(tl),
                                        busy_ns=sum(l['loop_ns'] for l in tl)))
            # main 행 — 활동 시간 합은 그 회차의 mainCPU 실측값(run 레코드). 없으면 None(옛 로그).
            first = min(run_scans, key=lambda x: x['scan'])
            last = max(run_scans, key=lambda x: x['scan'])
            st = conv(first['epoch_ms'])
            en = conv(last['epoch_ms']) + datetime.timedelta(microseconds=last['loop_ns'] / 1000)
            rec = next((x for x in runs if x['impl'] == '병렬'
                        and x['par'] == par and x['split'] == sp and x['run'] == rn), None)
            main_ns = rec['main_ns'] if rec else None
            thread_rows.append(dict(config=lab, run=rn, thread='main',
                                    role='main (활동 시간 합 = 그 회차 mainCPU, 창은 워커와 겹침)',
                                    start=st, end=en, units=len(run_scans), busy_ns=main_ns))
            # 풀 행 — 회차마다 새 풀이므로 그 회차 워커들의 창 합집합이 풀의 창
            pools = {}
            for l in run_leaves:
                m = re.match(r'(ForkJoinPool-\d+)-', l['thread'])
                if m:
                    pools.setdefault(m.group(1), []).append(l)
            for pool, pl in sorted(pools.items()):
                st = min(conv(l['epoch_ms']) for l in pl)
                en = max(conv(l['epoch_ms'])
                         + datetime.timedelta(microseconds=l['loop_ns'] / 1000) for l in pl)
                idxs = [i for i, (t, _, _) in enumerate(clock) if st <= t <= en]
                pool_rows.append(dict(config=lab, run=rn, pool=pool, start=st, end=en, samples=idxs))
            # main 행 — 창은 그 회차 스캔 전체(첫 스캔 시작 ~ 마지막 스캔 시작+바퀴)
            m_st = conv(first['epoch_ms'])
            m_en = conv(last['epoch_ms']) + datetime.timedelta(microseconds=last['loop_ns'] / 1000)
            m_idxs = [i for i, (t, _, _) in enumerate(clock) if m_st <= t <= m_en]
            pool_rows.append(dict(config=lab, run=rn, pool='main', start=m_st, end=m_en, samples=m_idxs))
    return thread_rows, pool_rows


def build_workbook(procs, clock, phases, thread_rows, pool_rows, runs, eval_by_run, base_ghz, out_path):
    wb = Workbook()

    def font(sz=10, bold=False, color='000000'):
        return Font(name=AR, size=sz, bold=bold, color=color)

    hdr_fill = PatternFill('solid', fgColor='D9D9D9')
    yellow = PatternFill('solid', fgColor='FFFF00')
    thin = Side(style='thin', color='999999')
    box = Border(left=thin, right=thin, top=thin, bottom=thin)

    # ── 시트 1: 클럭 정리 ──
    ws = wb.active
    ws.title = '클럭 정리'
    ws['A1'] = '논리 프로세서별 CPU 클럭, 표본 간격은 기록 도구 설정 (typeperf % Processor Performance)'
    ws['A1'].font = font(11, True)
    ws['A2'] = '기본 클럭(GHz)'
    ws['A2'].font = font(10, True)
    ws['B2'] = base_ghz
    ws['B2'].font = font(10, color='0000FF')
    ws['B2'].fill = yellow
    ws['C2'] = '출처: 작업 관리자 성능 탭의 기본 속도. GHz = 백분율 × 기본 클럭 ÷ 100'
    ws['C2'].font = font(9)
    ws['A3'] = '각 표본은 그 시각까지의 직전 표본 간격 구간의 평균이다. 백분율은 기본 클럭 대비 값이라 터보 구간에서 100을 넘는다.'
    ws['A3'].font = font(9)

    hdr_row = 5
    n_proc = len(procs)
    total_col = 2 + n_proc            # 전체 평균(%) 열 번호
    ghz_col = total_col + 1
    cols = ['시각'] + [f'논리 {p}(%)' for p in procs] + ['전체 평균(%)', '전체 평균(GHz)']
    for j, c in enumerate(cols, start=1):
        cell = ws.cell(row=hdr_row, column=j, value=c)
        cell.font = font(10, True)
        cell.fill = hdr_fill
        cell.border = box
    r = hdr_row + 1
    for t, per, tot in clock:
        ws.cell(row=r, column=1, value=t).number_format = 'hh:mm:ss.000'
        for j, v in enumerate(per, start=2):
            ws.cell(row=r, column=j, value=v).number_format = '0.0'
        ws.cell(row=r, column=total_col, value=tot).number_format = '0.0'
        g = ws.cell(row=r, column=ghz_col,
                    value=f'={get_column_letter(total_col)}{r}*$B$2/100')
        g.number_format = '0.00'
        r += 1
    last_data = r - 1
    for j in range(1, ghz_col + 1):
        ws.column_dimensions[get_column_letter(j)].width = 11
    ws.column_dimensions['A'].width = 13
    for row in ws.iter_rows(min_row=hdr_row + 1, max_row=last_data, max_col=ghz_col):
        for c in row:
            if c.font.name != AR:
                c.font = font(10)

    tcolL = get_column_letter(total_col)

    # ── 시트 2: 구간과 스레드 매핑 ──
    ws2 = wb.create_sheet('구간과 스레드 매핑')
    ws2['A1'] = '테스트 구간별 시각 경계와 실행 스레드, 그 구간의 평균 클럭'
    ws2['A1'].font = font(11, True)
    ws2['A2'] = ('시각 경계 출처: 전체·워밍업·워커 읽기는 프로브의 [구간 시각] 출력, '
                 '측정 회차는 IMPLOG의 시작시각ms(첫 스캔 시작)와 마지막 스캔 시작+바퀴 시간이다.')
    ws2['A2'].font = font(9)
    ws2['A3'] = ('클럭은 논리 프로세서별 값이고 스레드가 어느 프로세서에서 돌았는지는 기록되지 않으므로, '
                 '스레드별 대응은 구간 단위까지다. 각 표본이 직전 간격의 평균이라 경계 표본에는 이웃 구간의 몫이 섞일 수 있다.')
    ws2['A3'].font = font(9)
    headers = ['구간', '시작 시각', '끝 시각', '길이(초)', '실행 스레드', '표본 수(개)', '평균 클럭(%)', '평균 클럭(GHz)']
    for j, c in enumerate(headers, start=1):
        cell = ws2.cell(row=5, column=j, value=c)
        cell.font = font(10, True)
        cell.fill = hdr_fill
        cell.border = box
    rng_t = f"'클럭 정리'!$A${hdr_row + 1}:$A${last_data}"
    rng_v = f"'클럭 정리'!${tcolL}${hdr_row + 1}:${tcolL}${last_data}"
    r = 6
    phase_row = {}
    for name, st, en, thr, is_whole in phases:
        ws2.cell(row=r, column=1, value=name).font = font(10, is_whole)
        ws2.cell(row=r, column=2, value=st).number_format = 'hh:mm:ss.000'
        ws2.cell(row=r, column=3, value=en).number_format = 'hh:mm:ss.000'
        ws2.cell(row=r, column=4, value=f'=(C{r}-B{r})*86400').number_format = '0.00'
        ws2.cell(row=r, column=5, value=thr).font = font(9)
        ws2.cell(row=r, column=6,
                 value=f'=COUNTIFS({rng_t},">="&B{r},{rng_t},"<="&C{r})')
        # 구간이 표본 간격보다 짧으면 걸리는 표본이 0개라 평균을 낼 수 없다. 그때는 빈 칸으로 둔다
        ws2.cell(row=r, column=7,
                 value=f'=IFERROR(AVERAGEIFS({rng_v},{rng_t},">="&B{r},{rng_t},"<="&C{r}),"")').number_format = '0.0'
        ws2.cell(row=r, column=8, value=f'=IF(G{r}="","",G{r}*\'클럭 정리\'!$B$2/100)')
        ws2.cell(row=r, column=8).number_format = '0.00'
        ws2.cell(row=r, column=8).font = font(10, color='008000')
        phase_row[name] = r
        r += 1
    last_map = r - 1
    for j, w in enumerate([32, 14, 14, 9, 56, 11, 12, 13], start=1):
        ws2.column_dimensions[get_column_letter(j)].width = w
    for row in ws2.iter_rows(min_row=6, max_row=last_map, max_col=8):
        for c in row:
            c.border = box
            if c.font.name != AR:
                c.font = font(10)

    # ── 시트 3: 스레드별 클럭 ──
    ghzL = get_column_letter(ghz_col)
    ws4 = wb.create_sheet('스레드별 클럭')
    ws4['A1'] = '측정 회차 구간 안의 스레드별 활동 창과 클럭, 그리고 풀별 클럭 표본 매핑'
    ws4['A1'].font = font(11, True)
    ws4['A2'] = ('활동 창의 출처: 워커는 IMPLOG leaf(첫 말단 시작 ~ 마지막 말단 시작+4중 루프), main은 IMPLOG scan이다. '
                 '활동 시간 합은 워커가 4중 루프 경과 시간 합이고, main은 그 회차의 mainCPU(IMPLOG run 레코드, CPU 시간)다. '
                 '말단이 2개 이상 겹치면 경과 시간 뺄셈으로는 main 몫이 나오지 않아 실측 CPU 시간으로 바꿨다(2026-08-04). '
                 'run 레코드가 없는 옛 로그는 main의 이 칸이 빈 칸이고, mainCPU는 Windows 해상도(15.625ms) 계단이라 0으로 찍힐 수 있다.')
    ws4['A2'].font = font(9)
    ws4['A3'] = ('클럭 값은 그 창에 걸린 표본의 전체 프로세서 평균이다. 스레드가 어느 논리 프로세서에서 돌았는지는 기록되지 않으므로 '
                 '특정 코어의 클럭이 아니다. 워커별 CPU 읽기 실행의 풀은 IMPLOG가 꺼져 있어 이 시트에 없다. '
                 '클럭 두 열이 비어 있으면 그 활동 창에 걸린 표본이 0개라는 뜻이다. 표본 간격이 1초인데 창이 그보다 짧을 때 그렇게 된다.')
    ws4['A3'].font = font(9)

    headers4 = ['구간', '회차', '스레드', '역할', '활동 시작', '활동 끝',
                '처리 수(말단/스캔)', '활동 시간 합(ms)', '표본 수(개)', '평균 클럭(%)', '평균 클럭(GHz)']
    for j, c in enumerate(headers4, start=1):
        cell = ws4.cell(row=5, column=j, value=c)
        cell.font = font(10, True)
        cell.fill = hdr_fill
        cell.border = box
    r = 6
    for tr in thread_rows:
        ws4.cell(row=r, column=1, value=tr['config'])
        ws4.cell(row=r, column=2, value=tr['run'])
        ws4.cell(row=r, column=3, value=tr['thread'])
        ws4.cell(row=r, column=4, value=tr['role']).font = font(9)
        ws4.cell(row=r, column=5, value=tr['start']).number_format = 'hh:mm:ss.000'
        ws4.cell(row=r, column=6, value=tr['end']).number_format = 'hh:mm:ss.000'
        ws4.cell(row=r, column=7, value=tr['units'])
        if tr['busy_ns'] is not None:                       # None = run 레코드가 없는 옛 로그의 main 행 → 빈 칸
            ws4.cell(row=r, column=8, value=tr['busy_ns'] / 1e6).number_format = '0.0'
        ws4.cell(row=r, column=9,
                 value=f'=COUNTIFS({rng_t},">="&E{r},{rng_t},"<="&F{r})')
        # 활동 창이 표본 간격(1초)보다 짧으면 걸리는 표본이 0개다. 그때는 빈 칸으로 둔다(#DIV/0! 방지)
        ws4.cell(row=r, column=10,
                 value=f'=IFERROR(AVERAGEIFS({rng_v},{rng_t},">="&E{r},{rng_t},"<="&F{r}),"")').number_format = '0.0'
        c = ws4.cell(row=r, column=11, value=f'=IF(J{r}="","",J{r}*\'클럭 정리\'!$B$2/100)')
        c.number_format = '0.00'
        c.font = font(10, color='008000')
        r += 1
    last_thread = r - 1

    r += 2
    ws4.cell(row=r, column=1, value='풀·main별 클럭 표본 매핑').font = font(11, True)
    r += 1
    ws4.cell(row=r, column=1,
             value='행 하나가 (풀 또는 main, 그 활동 창에 걸린 클럭 표본 1개)다. 표본 값은 클럭 정리 시트의 해당 행을 참조한다. main의 창은 그 회차 스캔 전체라 같은 회차 풀과 같은 표본에 걸린다.').font = font(9)
    r += 1
    headers5 = ['구간', '회차', '풀/스레드', '활동 시작', '활동 끝', '표본 시각', '표본 클럭(%)', '표본 클럭(GHz)']
    for j, c in enumerate(headers5, start=1):
        cell = ws4.cell(row=r, column=j, value=c)
        cell.font = font(10, True)
        cell.fill = hdr_fill
        cell.border = box
    hdr5_row = r
    r += 1
    for pr in pool_rows:
        if not pr['samples']:
            ws4.cell(row=r, column=1, value=pr['config'])
            ws4.cell(row=r, column=2, value=pr['run'])
            ws4.cell(row=r, column=3, value=pr['pool'])
            ws4.cell(row=r, column=4, value=pr['start']).number_format = 'hh:mm:ss.000'
            ws4.cell(row=r, column=5, value=pr['end']).number_format = 'hh:mm:ss.000'
            ws4.cell(row=r, column=6, value='(걸린 표본 없음)')
            r += 1
            continue
        for i in pr['samples']:
            src = hdr_row + 1 + i          # 클럭 정리 시트에서 이 표본이 놓인 행
            ws4.cell(row=r, column=1, value=pr['config'])
            ws4.cell(row=r, column=2, value=pr['run'])
            ws4.cell(row=r, column=3, value=pr['pool'])
            ws4.cell(row=r, column=4, value=pr['start']).number_format = 'hh:mm:ss.000'
            ws4.cell(row=r, column=5, value=pr['end']).number_format = 'hh:mm:ss.000'
            c = ws4.cell(row=r, column=6, value=f"='클럭 정리'!A{src}")
            c.number_format = 'hh:mm:ss.000'
            c.font = font(10, color='008000')
            c = ws4.cell(row=r, column=7, value=f"='클럭 정리'!{tcolL}{src}")
            c.number_format = '0.0'
            c.font = font(10, color='008000')
            c = ws4.cell(row=r, column=8, value=f"='클럭 정리'!{ghzL}{src}")
            c.number_format = '0.00'
            c.font = font(10, color='008000')
            r += 1
    last_pool = r - 1
    for j, w in enumerate([20, 6, 26, 40, 14, 14, 17, 15, 11, 12, 13], start=1):
        ws4.column_dimensions[get_column_letter(j)].width = w
    for row in ws4.iter_rows(min_row=6, max_row=last_thread, max_col=11):
        for c in row:
            c.border = box
            if c.font.name != AR:
                c.font = font(10)
    for row in ws4.iter_rows(min_row=hdr5_row + 1, max_row=last_pool, max_col=8):
        for c in row:
            c.border = box
            if c.font.name != AR:
                c.font = font(10)

    # ── 시트: 회차별 측정값 (IMPLOG run 레코드가 있을 때만 만든다) ──
    if runs:
        wsr = wb.create_sheet('회차별 측정값')
        wsr['A1'] = '측정 회차별 원값 (IMPLOG run 레코드). 프로브 표의 행은 5회 평균이고, 이 시트는 그 5회 각각이다'
        wsr['A1'].font = font(11, True)
        wsr['A2'] = ('busyCore와 speedup은 파생값이라 로그에 없고 여기서 수식으로 계산한다. '
                     'busyCore는 procCPU를 elapsed로 나눈 값, speedup은 순차 elapsed의 5회 평균을 그 행의 elapsed로 나눈 값이다. '
                     '스레드 CPU 시간(mainCPU 등)은 Windows 해상도(15.625ms) 계단이라 작은 값은 0으로 찍힌다.')
        wsr['A2'].font = font(9)
        wsr['A3'] = ('할당 열은 그 회차에 새로 만든 객체의 바이트다. main·워커·기타를 더한 값이 총 할당이고, '
                     '평가 1회당 할당은 총 할당을 그 회차의 평가 횟수로 나눈 값이다. 평가 횟수는 IMPLOG leaf의 sumDev 실행 횟수 합이다. '
                     '순차 행은 말단 기록이 없어 평가 횟수와 평가 1회당 할당이 빈 칸이다. MB는 1,000,000바이트 기준이다.')
        wsr['A3'].font = font(9)
        headers_r = ['구현', '병렬도', '분할', '회차', 'elapsed(ms)', 'procCPU(ms)', 'busyCore(개)',
                     'mainCPU(ms)', 'workerCPU(ms)', '기타 자바(ms)', '자바 아닌(ms)', 'GC(ms)', 'speedup(배)',
                     'main 할당(MB)', '워커 할당(MB)', '기타 할당(MB)', '총 할당(MB)',
                     '평가 횟수(회)', '평가 1회당 할당(B)']
        for j, c in enumerate(headers_r, start=1):
            cell = wsr.cell(row=4, column=j, value=c)
            cell.font = font(10, True)
            cell.fill = hdr_fill
            cell.border = box
        run_first = 5
        run_last = run_first + len(runs) - 1
        seq_rng_a = f'$A${run_first}:$A${run_last}'
        seq_rng_e = f'$E${run_first}:$E${run_last}'
        rr = run_first
        for rec in runs:
            wsr.cell(row=rr, column=1, value=rec['impl'])
            wsr.cell(row=rr, column=2, value=rec['par'] if rec['impl'] == '병렬' else '-')
            wsr.cell(row=rr, column=3, value=rec['split'] if rec['impl'] == '병렬' else '-')
            wsr.cell(row=rr, column=4, value=rec['run'])
            wsr.cell(row=rr, column=5, value=rec['elapsed_ns'] / 1e6).number_format = '0.0'
            wsr.cell(row=rr, column=6, value=rec['proc_ns'] / 1e6).number_format = '0.0'
            wsr.cell(row=rr, column=7, value=f'=F{rr}/E{rr}').number_format = '0.00'
            wsr.cell(row=rr, column=8, value=rec['main_ns'] / 1e6).number_format = '0.0'
            wsr.cell(row=rr, column=9, value=rec['worker_ns'] / 1e6).number_format = '0.0'
            wsr.cell(row=rr, column=10, value=rec['other_ns'] / 1e6).number_format = '0.0'
            wsr.cell(row=rr, column=11, value=rec['nonjava_ns'] / 1e6).number_format = '0.0'
            wsr.cell(row=rr, column=12, value=rec['gc_ms'])
            if rec['impl'] == '병렬':
                wsr.cell(row=rr, column=13,
                         value=f'=AVERAGEIFS({seq_rng_e},{seq_rng_a},"순차")/E{rr}').number_format = '0.00'

            # 할당 열 — run 레코드에 세 칸이 있는 로그에서만 채운다.
            if rec['main_alloc_b'] is not None:
                wsr.cell(row=rr, column=14, value=rec['main_alloc_b'] / 1e6).number_format = '#,##0.0'
                wsr.cell(row=rr, column=15, value=rec['worker_alloc_b'] / 1e6).number_format = '#,##0.0'
                wsr.cell(row=rr, column=16, value=rec['other_alloc_b'] / 1e6).number_format = '#,##0.0'
                wsr.cell(row=rr, column=17, value=f'=N{rr}+O{rr}+P{rr}').number_format = '#,##0.0'
                # 평가 횟수는 말단 기록에서 온다. 순차 행은 말단이 없어 빈 칸로 둔다.
                ev = eval_by_run.get((rec['par'], rec['split'], rec['run'])) if rec['impl'] == '병렬' else None
                if ev:
                    wsr.cell(row=rr, column=18, value=ev).number_format = '#,##0'
                    wsr.cell(row=rr, column=19, value=f'=Q{rr}*1000000/R{rr}').number_format = '#,##0'
            rr += 1
        for j, w in enumerate([7, 8, 7, 6, 12, 12, 11, 11, 13, 12, 12, 8, 11,
                               14, 15, 15, 13, 13, 18], start=1):
            wsr.column_dimensions[get_column_letter(j)].width = w
        for row in wsr.iter_rows(min_row=run_first, max_row=run_last, max_col=len(headers_r)):
            for c in row:
                c.border = box
                if c.font.name != AR:
                    c.font = font(10)

    # ── 시트 4: 차트 ──
    # 표 형식은 상세 로그 엑셀(예: '평가 1회당 루프 시간(ns)' 시트)을 따른다.
    # 설명 2줄 + 3행 머리줄(세로 = 구간 종류, 가로 = 설정), 값은 매핑 시트의 평균 클럭(GHz)을 수식으로 참조한다.
    ws3 = wb.create_sheet('차트')
    ws3['A1'] = "이 시트의 값: '구간과 스레드 매핑' 시트의 평균 클럭(GHz) 열이다. 수식 참조로 그대로 가져왔다"
    ws3['A1'].font = font(10)
    ws3['A2'] = ('차트: 전체 추이 선(전체 평균과 스레드별 선), 구간별 평균 막대, 측정 회차마다 스레드별 클럭 선 그래프다. '
                 '스레드별 선은 그 스레드의 활동 창에 걸린 표본만 그린다. 분할 1처럼 창이 겹치면 선도 겹친다. '
                 "그래프의 원천 표는 '차트 데이터' 시트에 있다")
    ws3['A2'].font = font(10)

    cfg_labels = []                     # 가로 열 = 설정 (순차, 병렬도 p 분할 s ...)
    if any(n == '순차 전체' for n, *_ in phases):
        cfg_labels.append('순차')
    for n, *_ in phases:
        m = re.match(r'(병렬도 \d+ 분할 \d+) 전체$', n)
        if m and m.group(1) not in cfg_labels:
            cfg_labels.append(m.group(1))
    kinds = ['전체', '워밍업']          # 세로 행 = 구간 종류
    run_nos = sorted({int(m.group(1)) for n, *_ in phases
                      for m in [re.search(r'측정 (\d+)회차$', n)] if m})
    kinds += [f'측정 {rn}회차' for rn in run_nos]
    kinds += ['워커별 CPU 읽기 실행']

    ws3.cell(row=3, column=1, value='구간 종류').font = font(10, True)
    ws3.cell(row=3, column=1).fill = hdr_fill
    for j, lab in enumerate(cfg_labels, start=2):
        c = ws3.cell(row=3, column=j, value=lab)
        c.font = font(10, True)
        c.fill = hdr_fill
    for i, kind in enumerate(kinds):
        rr = 4 + i
        ws3.cell(row=rr, column=1, value=kind).font = font(10)
        for j, lab in enumerate(cfg_labels, start=2):
            name = f'{lab} {kind}' if lab != '순차' else ('순차 전체' if kind == '전체' else None)
            if name in phase_row:
                c = ws3.cell(row=rr, column=j, value=f"='구간과 스레드 매핑'!H{phase_row[name]}")
                c.number_format = '0.00'
                c.font = font(10, color='008000')
    tbl_last = 3 + len(kinds)
    ws3.column_dimensions['A'].width = 22
    for j in range(2, 2 + len(cfg_labels)):
        ws3.column_dimensions[get_column_letter(j)].width = 18

    def style_axes(ch, cat_skip=None):
        """모든 그래프에 공통인 축 표기: y = 클럭(GHz) 눈금 0.2 간격, x = 시간, 눈금 수치 표시."""
        ch.y_axis.title = '클럭(GHz)'
        ch.x_axis.title = '시간'
        ch.y_axis.scaling.min, ch.y_axis.scaling.max = 3.0, 4.6
        ch.y_axis.majorUnit = 0.2
        ch.y_axis.number_format = '0.0'
        ch.y_axis.majorTickMark = 'out'
        ch.y_axis.tickLblPos = 'nextTo'
        ch.y_axis.delete = False
        ch.x_axis.majorTickMark = 'out'
        ch.x_axis.tickLblPos = 'low'
        ch.x_axis.delete = False
        if cat_skip:
            ch.x_axis.tickLblSkip = cat_skip      # 표본이 많은 축은 눈금 이름을 몇 개 건너뛰며 찍는다
            ch.x_axis.tickMarkSkip = cat_skip

    run_keys = []
    for tr in thread_rows:
        k = (tr['config'], tr['run'])
        if k not in run_keys:
            run_keys.append(k)
    main_window = {(pr['config'], pr['run']): pr for pr in pool_rows if pr['pool'] == 'main'}

    # 그래프 원천 데이터는 전부 별도 시트('차트 데이터')에 둔다 — 차트 시트에는 교차표와 그래프만 남긴다.
    ws_data = wb.create_sheet('차트 데이터')
    ws_data['A1'] = "차트 시트 그래프들의 원천 표다. 값은 전부 '클럭 정리' 시트를 참조하는 수식이고, 여기서는 보기만 한다"
    ws_data['A1'].font = font(9)
    blk_col = 1
    blk_row = 3
    blocks = []                    # (제목, 머리줄 행, 표본 수, 스레드 수)
    max_thr = 1
    for cfg, rn in run_keys:
        thrs = [tr for tr in thread_rows if tr['config'] == cfg and tr['run'] == rn]
        max_thr = max(max_thr, len(thrs))
        win = main_window.get((cfg, rn))
        if win is None or not win['samples']:
            continue
        title = f'{cfg} 측정 {rn}회차'
        ws_data.cell(row=blk_row, column=blk_col, value=title + ' (스레드별 클럭, GHz)').font = font(10, True)
        hr = blk_row + 1
        ws_data.cell(row=hr, column=blk_col, value='시각').font = font(9, True)
        for j, tr in enumerate(thrs, start=1):
            ws_data.cell(row=hr, column=blk_col + j, value=tr['thread']).font = font(9, True)
        for i, si2 in enumerate(win['samples'], start=1):
            src_row = hdr_row + 1 + si2
            c = ws_data.cell(row=hr + i, column=blk_col, value=f"='클럭 정리'!A{src_row}")
            c.number_format = 'hh:mm:ss.000'
            c.font = font(9, color='008000')
            t_sample = clock[si2][0]
            for j, tr in enumerate(thrs, start=1):
                if tr['start'] <= t_sample <= tr['end']:
                    c = ws_data.cell(row=hr + i, column=blk_col + j, value=f"='클럭 정리'!{ghzL}{src_row}")
                    c.number_format = '0.00'
                    c.font = font(9, color='008000')
        for row in ws_data.iter_rows(min_row=hr, max_row=hr + len(win['samples']),
                                     min_col=blk_col, max_col=blk_col + len(thrs)):
            for c in row:
                c.border = box
                if c.row == hr:
                    c.fill = hdr_fill
        blocks.append((title, hr, len(win['samples']), len(thrs)))
        blk_row = hr + len(win['samples']) + 2

    # 전체 추이용 데이터 블록 — 전 표본에 대해 전체 평균과 스레드별 열을 만든다(활동 창 밖은 빈 칸 = 선 끊김).
    windows = {}                   # 스레드 이름 → 활동 창 목록 (main은 회차마다 하나씩 여러 개)
    order = []
    for tr in thread_rows:
        windows.setdefault(tr['thread'], []).append((tr['start'], tr['end']))
        if tr['thread'] not in order:
            order.append(tr['thread'])
    series_names = ['전체 평균'] + order
    ov_col = blk_col + max_thr + 2
    ws_data.cell(row=3, column=ov_col, value='전체 추이 데이터 (스레드별 선용, GHz)').font = font(10, True)
    ws_data.cell(row=4, column=ov_col, value='시각').font = font(9, True)
    for j, nm in enumerate(series_names, start=1):
        ws_data.cell(row=4, column=ov_col + j, value=nm).font = font(9, True)
    for i in range(len(clock)):
        src_row = hdr_row + 1 + i
        rr = 5 + i
        c = ws_data.cell(row=rr, column=ov_col, value=f"='클럭 정리'!A{src_row}")
        c.number_format = 'hh:mm:ss'
        c.font = font(9, color='008000')
        c = ws_data.cell(row=rr, column=ov_col + 1, value=f"='클럭 정리'!{ghzL}{src_row}")
        c.number_format = '0.00'
        c.font = font(9, color='008000')
        t_sample = clock[i][0]
        for j, nm in enumerate(order, start=2):
            if any(s <= t_sample <= e for s, e in windows[nm]):
                c = ws_data.cell(row=rr, column=ov_col + j, value=f"='클럭 정리'!{ghzL}{src_row}")
                c.number_format = '0.00'
                c.font = font(9, color='008000')
    ov_last = 4 + len(clock)
    for row in ws_data.iter_rows(min_row=4, max_row=ov_last,
                                 min_col=ov_col, max_col=ov_col + len(series_names)):
        for c in row:
            c.border = box
            if c.row == 4:
                c.fill = hdr_fill
    for j in range(1, ov_col + len(series_names) + 1):
        ws_data.column_dimensions[get_column_letter(j)].width = 24

    lc = LineChart()
    lc.title = '전체 평균 클럭 추이 (전체 평균 + 스레드별)'
    lc.height, lc.width = 10, 30
    lc.add_data(Reference(ws_data, min_col=ov_col + 1, max_col=ov_col + len(series_names),
                          min_row=4, max_row=ov_last), titles_from_data=True)
    lc.set_categories(Reference(ws_data, min_col=ov_col, min_row=5, max_row=ov_last))
    style_axes(lc, cat_skip=10)
    lc.varyColors = False
    lc.legend.position = 'r'
    lc.legend.overlay = False      # 범례를 그림 영역 밖 오른쪽에 둔다 — 선과 겹치지 않게
    ws3.add_chart(lc, 'A13')

    bc = BarChart()
    bc.title = '구간별 평균 클럭'
    bc.height, bc.width = 8, 20
    bc.add_data(Reference(ws3, min_col=2, max_col=1 + len(cfg_labels), min_row=3, max_row=tbl_last),
                titles_from_data=True)
    bc.set_categories(Reference(ws3, min_col=1, min_row=4, max_row=tbl_last))
    style_axes(bc)
    bc.x_axis.title = '구간 종류'
    bc.varyColors = False
    bc.legend.overlay = False
    ws3.add_chart(bc, 'A31')

    chart_row = 48
    for title, hr, n_s, n_thr in blocks:
        rc = LineChart()
        rc.title = title + ' 스레드별 클럭'
        rc.height, rc.width = 7, 16
        rc.add_data(Reference(ws_data, min_col=blk_col + 1, max_col=blk_col + n_thr,
                              min_row=hr, max_row=hr + n_s), titles_from_data=True)
        rc.set_categories(Reference(ws_data, min_col=blk_col, min_row=hr + 1, max_row=hr + n_s))
        style_axes(rc)
        rc.varyColors = False
        rc.legend.position = 'r'
        rc.legend.overlay = False
        ws3.add_chart(rc, f'A{chart_row}')
        chart_row += 15

    try:
        wb.save(out_path)
    except PermissionError:
        raise SystemExit(f'출력 파일을 쓸 수 없다: {out_path}\n같은 이름의 엑셀 파일이 열려 있으면 닫고 다시 실행한다.')


def main():
    ap = argparse.ArgumentParser(description='클럭 CSV와 프로브 콘솔 로그를 묶어 클럭 정리 엑셀을 만든다')
    ap.add_argument('clock_csv', help='typeperf가 남긴 클럭 CSV 경로')
    ap.add_argument('console_log', help='프로브 콘솔 로그(txt) 경로')
    ap.add_argument('-o', '--output', required=True, help='출력 xlsx 경로')
    ap.add_argument('--base', type=float, default=2.69, help='기본 클럭 GHz (기본값 2.69)')
    args = ap.parse_args()

    procs, clock = parse_clock_csv(args.clock_csv)
    date = clock[0][0].date()
    marks, scans, leaves, ledger, runs = parse_console_log(read_text(args.console_log), date)
    if not marks:
        raise SystemExit('콘솔 로그에서 [구간 시각] 줄을 찾지 못했다. 프로브가 구간 시각 출력이 있는 판인지 확인할 것')
    offset = epoch_offset(marks, scans)
    phases = build_phases(marks, scans, leaves, ledger, offset)
    if not phases:
        raise SystemExit('구간을 하나도 만들지 못했다. 로그 내용을 확인할 것')
    thread_rows, pool_rows = build_thread_and_pool_rows(marks, scans, leaves, offset, clock, runs)

    # 회차별 평가 횟수 = 그 회차 말단들의 sumDev 실행 횟수 합. 평가 1회당 할당을 내는 데 쓴다.
    eval_by_run = {}
    for lf in leaves:
        key = (lf['par'], lf['split'], lf['run'])
        eval_by_run[key] = eval_by_run.get(key, 0) + lf['eval_count']

    build_workbook(procs, clock, phases, thread_rows, pool_rows, runs, eval_by_run, args.base, args.output)
    if not runs:
        print('알림: 콘솔 로그에 IMPLOG run 레코드가 없다(회차별 로그 추가 전 프로브의 옛 로그). '
              '회차별 측정값 시트는 만들지 않았고, 스레드별 클럭 시트의 main 행 활동 시간 합은 빈 칸이다.')
    elif all(rec['main_alloc_b'] is None for rec in runs):
        print('알림: run 레코드에 할당 바이트 세 칸이 없다(할당 계측을 넣기 전 프로브의 로그). '
              '회차별 측정값 시트의 할당 열은 빈 칸이다.')

    print(f'표본 {len(clock)}개, 구간 {len(phases)}개 → {args.output}')
    print('구간 목록:')
    for name, st, en, _, _ in phases:
        print(f'  {name}: {st.time()} ~ {en.time()}')


if __name__ == '__main__':
    main()
