# clocklog_summary_to_xlsx.py — 분할별 클럭 원본(CSV + 콘솔 로그) 쌍을 전부 읽어 종합 분석 엑셀을 만든다.
#
# 입력: 폴더 하나. 그 안에서 아래 이름 규칙의 쌍을 전부 찾는다.
#   병렬도6과12_말단작업분할수준_{분할}_원본데이터[_판번호].csv
#   병렬도6과12_말단작업분할수준_{분할}_원본데이터[_판번호]_ConsoleLog.txt
#   같은 분할의 쌍이 여러 개면(재측정) CSV 수정 시각이 가장 늦은 것을 대표로 쓰고, 나머지는 구간 상세에만 참고로 남긴다.
# 출력 시트:
#   종합          : 분할별 대표 실행의 구간 평균 클럭(순차·병렬도 6·병렬도 12)과 순차 대비 하락률.
#   구간 상세      : 실행(파일)별 세 구간의 시각 경계·표본 수·평균·최소·최대.
#   회차별 원값 종합 : 모든 실행의 IMPLOG run 레코드(측정 회차별 원값). run 레코드가 없는 옛 로그 실행은 이 시트에 없다.
#   차트          : 분할별 클럭 선 그래프와 지표별 선 그래프(elapsed·procCPU·busyCore·mainCPU·workerCPU·기타 자바·자바 아닌).
#   차트 데이터    : 지표별 그래프의 원천 표. 분할마다 대표 실행의 병렬 측정 5회 평균이다.
# 주의:
#   구간 경계는 콘솔 로그의 [구간 시각] 줄이고 CSV 표본과 하루 안 시각으로 맞춘다. 자정을 넘긴 실행은 지원하지 않는다.
#   CSV는 typeperf 원본(따옴표 있음)과 엑셀로 다시 저장한 판(따옴표 없음)을 모두 읽는다.
# 실행 예:
#   py -3 clocklog_summary_to_xlsx.py . -o 병렬도6과12_클럭종합분석.xlsx
# 필요한 패키지: openpyxl (py -3 -m pip install openpyxl)

import argparse
import csv
import datetime
import os
import re
import sys

from openpyxl import Workbook
from openpyxl.chart import LineChart, Reference
from openpyxl.styles import Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

AR = 'Arial'

PAIR_RE = re.compile(r'^(병렬도6과12_말단작업분할수준_(\d+)_원본데이터(?:_\d+)?)\.csv$')
MARK_RE = re.compile(r'\[구간 시각 · 임시\]\s+(\d+):(\d+):(\d+)\.(\d+)\s+(시작|끝)\s+·\s+(.+)')
CFG_RE = re.compile(r'병렬도 (\d+) 분할 (\d+)$')


def read_text(path):
    for enc in ('utf-8-sig', 'utf-8', 'cp949'):
        try:
            with open(path, encoding=enc) as f:
                return f.read().splitlines()
        except UnicodeDecodeError:
            continue
    raise SystemExit(f'인코딩을 알 수 없다: {path}')


def parse_clock_csv(path):
    """[(하루 안 초, 전체 평균 %)] 반환. 값이 빈 첫 표본은 버린다. 전체 평균은 (_Total) 열이다."""
    rows = []
    with open(path, encoding='utf-8-sig', newline='') as f:
        r = csv.reader(f)
        header = next(r)
        total_idx = None
        for i, h in enumerate(header[1:]):
            if '(_Total)' in h:
                total_idx = i          # (0,_Total)과 (_Total)이 둘 다 있으면 뒤의 것이 남는다(기존 스크립트와 같은 규칙)
        if total_idx is None:
            raise SystemExit(f'CSV 머리줄에서 (_Total) 열을 찾지 못했다: {path}')
        for line in r:
            if len(line) < 2 or line[1].strip() == '' or line[1:][total_idx].strip() == '':
                continue          # 첫 표본: 직전 값이 없어 빈 칸이다
            t = datetime.datetime.strptime(line[0].strip(), '%m/%d/%Y %H:%M:%S.%f')
            sec = t.hour * 3600 + t.minute * 60 + t.second + t.microsecond / 1e6
            rows.append((sec, float(line[1:][total_idx])))
    if not rows:
        raise SystemExit(f'CSV에 값 있는 표본이 없다: {path}')
    return rows


def parse_log(path):
    """([구간 시각] 표시 목록, IMPLOG run 레코드 목록) 반환.
    marks: (하루 안 초, '시작'|'끝', 라벨). runs: 회차별 원값 dict."""
    marks, runs = [], []
    for line in read_text(path):
        m = MARK_RE.match(line)
        if m:
            us = int((m.group(4) + '0' * 6)[:6])
            sec = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) + us / 1e6
            label = re.sub(r'\s*\(N=\d+\)\s*$', '', m.group(6)).strip()
            marks.append((sec, m.group(5), label))
            continue
        if line.startswith('IMPLOG,run,'):
            p = line.split(',')
            runs.append(dict(impl=p[2], par=int(p[3]), split=int(p[4]), run=int(p[5]),
                             elapsed_ns=int(p[6]), proc_ns=int(p[7]), main_ns=int(p[8]),
                             worker_ns=int(p[9]), other_ns=int(p[10]), nonjava_ns=int(p[11]),
                             gc_ms=int(p[12])))
    return marks, runs


def window_stats(clock, st, en):
    """구간 [st, en] 초 안에 걸린 표본의 (개수, 평균 %, 최소 %, 최대 %). 표본이 없으면 None."""
    vals = [v for t, v in clock if st <= t <= en]
    if not vals:
        return None
    return dict(n=len(vals), avg=sum(vals) / len(vals), mn=min(vals), mx=max(vals))


def collect(folder):
    """폴더의 모든 쌍을 읽어 실행 목록을 만든다. 실행 하나 = dict(split, stem, mtime, windows, runs, rep)."""
    entries = []
    for name in sorted(os.listdir(folder)):
        m = PAIR_RE.match(name)
        if not m:
            continue
        stem, split = m.group(1), int(m.group(2))
        csv_path = os.path.join(folder, name)
        log_path = os.path.join(folder, stem + '_ConsoleLog.txt')
        if not os.path.exists(log_path):
            print(f'건너뜀(콘솔 로그 없음): {name}')
            continue
        clock = parse_clock_csv(csv_path)
        marks, runs = parse_log(log_path)
        if not marks:
            print(f'건너뜀([구간 시각] 없음): {stem}')
            continue

        def find(label, kind):
            for t, k, l in marks:
                if k == kind and l == label:
                    return t
            return None

        windows = {}
        st, en = find('순차', '시작'), find('순차', '끝')
        if st is not None and en is not None:
            stats = window_stats(clock, st, en)
            if stats:
                windows['순차'] = dict(st=st, en=en, **stats)
        for t, k, l in marks:
            cm = CFG_RE.match(l)
            if cm and k == '시작':
                par = int(cm.group(1))
                en2 = find(l, '끝')
                if en2 is None:
                    continue
                stats = window_stats(clock, t, en2)
                if stats:
                    windows[('병렬', par)] = dict(st=t, en=en2, **stats)
        entries.append(dict(split=split, stem=stem, mtime=os.path.getmtime(csv_path),
                            windows=windows, runs=runs, rep=False))
    if not entries:
        raise SystemExit('처리할 (CSV, 콘솔 로그) 쌍을 하나도 찾지 못했다. 폴더와 파일 이름 규칙을 확인할 것.')
    # 분할마다 CSV 수정 시각이 가장 늦은 실행을 대표로 표시한다
    by_split = {}
    for e in entries:
        cur = by_split.get(e['split'])
        if cur is None or e['mtime'] > cur['mtime']:
            by_split[e['split']] = e
    for e in by_split.values():
        e['rep'] = True
    return entries, by_split


def sec_to_time(sec):
    h = int(sec // 3600); m2 = int(sec % 3600 // 60); s = sec % 60
    return f'{h:02d}:{m2:02d}:{s:06.3f}'


def build(entries, by_split, base_ghz, out_path):
    wb = Workbook()

    def font(sz=10, bold=False, color='000000'):
        return Font(name=AR, size=sz, bold=bold, color=color)

    hdr_fill = PatternFill('solid', fgColor='D9D9D9')
    yellow = PatternFill('solid', fgColor='FFFF00')
    thin = Side(style='thin', color='999999')
    box = Border(left=thin, right=thin, top=thin, bottom=thin)

    def write_header(ws, row, headers):
        for j, c in enumerate(headers, start=1):
            cell = ws.cell(row=row, column=j, value=c)
            cell.font = font(10, True)
            cell.fill = hdr_fill
            cell.border = box

    def box_rows(ws, r1, r2, cmax):
        for row in ws.iter_rows(min_row=r1, max_row=r2, max_col=cmax):
            for c in row:
                c.border = box
                if c.font.name != AR:
                    c.font = font(10)

    splits = sorted(by_split)

    # ── 시트 1: 종합 ──
    ws = wb.active
    ws.title = '종합'
    ws['A1'] = '분할별 구간 평균 클럭 종합 (분할마다 대표 실행 하나, 재측정이 있으면 가장 최근 판)'
    ws['A1'].font = font(11, True)
    ws['A2'] = '기본 클럭(GHz)'
    ws['A2'].font = font(10, True)
    ws['B2'] = base_ghz
    ws['B2'].font = font(10, color='0000FF')
    ws['B2'].fill = yellow
    ws['C2'] = 'GHz = typeperf 백분율의 구간 평균 × 기본 클럭 ÷ 100. 동시 실행 = 분할과 병렬도 중 작은 쪽.'
    ws['C2'].font = font(9)
    hdr = 4
    headers = ['분할', '동시 실행(병렬도 6)', '동시 실행(병렬도 12)',
               '순차(GHz)', '병렬도 6(GHz)', '병렬도 12(GHz)',
               '순차 대비 하락, 병렬도 6(%)', '순차 대비 하락, 병렬도 12(%)',
               '표본 수(순차)', '표본 수(병렬 6)', '표본 수(병렬 12)', '대표 실행 파일']
    write_header(ws, hdr, headers)
    r = hdr + 1
    row_of_split = {}
    for sp in splits:
        e = by_split[sp]
        w = e['windows']
        seq, p6, p12 = w.get('순차'), w.get(('병렬', 6)), w.get(('병렬', 12))
        ws.cell(row=r, column=1, value=sp)
        ws.cell(row=r, column=2, value=min(sp, 6))
        ws.cell(row=r, column=3, value=min(sp, 12))
        for col, st_ in ((4, seq), (5, p6), (6, p12)):
            if st_:
                ws.cell(row=r, column=col, value=st_['avg'] * base_ghz / 100).number_format = '0.000'
        if seq and p6:
            ws.cell(row=r, column=7, value=f'=(D{r}-E{r})/D{r}*100').number_format = '0.0'
        if seq and p12:
            ws.cell(row=r, column=8, value=f'=(D{r}-F{r})/D{r}*100').number_format = '0.0'
        for col, st_ in ((9, seq), (10, p6), (11, p12)):
            ws.cell(row=r, column=col, value=st_['n'] if st_ else None)
        ws.cell(row=r, column=12, value=e['stem']).font = font(9)
        row_of_split[sp] = r
        r += 1
    last = r - 1
    box_rows(ws, hdr + 1, last, len(headers))
    for j, w_ in enumerate([6, 17, 18, 10, 13, 13, 22, 23, 12, 13, 13, 44], start=1):
        ws.column_dimensions[get_column_letter(j)].width = w_

    # ── 시트 2: 구간 상세 ──
    ws3 = wb.create_sheet('구간 상세')
    ws3['A1'] = '실행(파일)별 세 구간의 시각 경계와 표본. 대표 = 종합 시트에 쓴 실행.'
    ws3['A1'].font = font(11, True)
    hdr3 = 3
    write_header(ws3, hdr3, ['분할', '실행 파일', '대표', '구간', '시작', '끝', '길이(초)',
                             '표본 수(개)', '평균(%)', '평균(GHz)', '최소(GHz)', '최대(GHz)'])
    r = hdr3 + 1
    for e in sorted(entries, key=lambda x: (x['split'], x['stem'])):
        for key, name in (('순차', '순차'), (('병렬', 6), f"병렬도 6 분할 {e['split']}"),
                          (('병렬', 12), f"병렬도 12 분할 {e['split']}")):
            w = e['windows'].get(key)
            if not w:
                continue
            ws3.cell(row=r, column=1, value=e['split'])
            ws3.cell(row=r, column=2, value=e['stem']).font = font(9)
            ws3.cell(row=r, column=3, value='대표' if e['rep'] else '참고')
            ws3.cell(row=r, column=4, value=name)
            ws3.cell(row=r, column=5, value=sec_to_time(w['st']))
            ws3.cell(row=r, column=6, value=sec_to_time(w['en']))
            ws3.cell(row=r, column=7, value=w['en'] - w['st']).number_format = '0.00'
            ws3.cell(row=r, column=8, value=w['n'])
            ws3.cell(row=r, column=9, value=w['avg']).number_format = '0.0'
            ws3.cell(row=r, column=10, value=f"=I{r}*'종합'!$B$2/100").number_format = '0.000'
            ws3.cell(row=r, column=11, value=w['mn'] * base_ghz / 100).number_format = '0.00'
            ws3.cell(row=r, column=12, value=w['mx'] * base_ghz / 100).number_format = '0.00'
            r += 1
    box_rows(ws3, hdr3 + 1, r - 1, 12)
    for j, w_ in enumerate([6, 44, 6, 20, 13, 13, 9, 11, 9, 11, 10, 10], start=1):
        ws3.column_dimensions[get_column_letter(j)].width = w_

    # ── 시트 4: 회차별 원값 종합 ──
    ws4 = wb.create_sheet('회차별 원값 종합')
    ws4['A1'] = '모든 실행의 IMPLOG run 레코드(측정 회차별 원값). run 레코드가 없는 옛 로그 실행은 여기 없다.'
    ws4['A1'].font = font(11, True)
    ws4['A2'] = ('busyCore = procCPU ÷ elapsed. speedup = 같은 실행의 순차 elapsed 5회 평균 ÷ 그 행 elapsed. '
                 '스레드 CPU 시간은 Windows 해상도(15.625ms) 계단이라 작은 값은 0으로 찍힌다.')
    ws4['A2'].font = font(9)
    hdr4 = 4
    write_header(ws4, hdr4, ['분할', '실행 파일', '대표', '구현', '병렬도', '회차',
                             'elapsed(ms)', 'procCPU(ms)', 'busyCore(개)', 'mainCPU(ms)',
                             'workerCPU(ms)', '기타 자바(ms)', '자바 아닌(ms)', 'GC(ms)', 'speedup(배)'])
    r = hdr4 + 1
    for e in sorted(entries, key=lambda x: (x['split'], x['stem'])):
        if not e['runs']:
            continue
        seq_el = [x['elapsed_ns'] for x in e['runs'] if x['impl'] == '순차']
        seq_avg = sum(seq_el) / len(seq_el) if seq_el else None
        for x in e['runs']:
            ws4.cell(row=r, column=1, value=e['split'])
            ws4.cell(row=r, column=2, value=e['stem']).font = font(9)
            ws4.cell(row=r, column=3, value='대표' if e['rep'] else '참고')
            ws4.cell(row=r, column=4, value=x['impl'])
            ws4.cell(row=r, column=5, value=x['par'] if x['impl'] == '병렬' else '-')
            ws4.cell(row=r, column=6, value=x['run'])
            ws4.cell(row=r, column=7, value=x['elapsed_ns'] / 1e6).number_format = '0.0'
            ws4.cell(row=r, column=8, value=x['proc_ns'] / 1e6).number_format = '0.0'
            ws4.cell(row=r, column=9, value=f'=H{r}/G{r}').number_format = '0.00'
            ws4.cell(row=r, column=10, value=x['main_ns'] / 1e6).number_format = '0.0'
            ws4.cell(row=r, column=11, value=x['worker_ns'] / 1e6).number_format = '0.0'
            ws4.cell(row=r, column=12, value=x['other_ns'] / 1e6).number_format = '0.0'
            ws4.cell(row=r, column=13, value=x['nonjava_ns'] / 1e6).number_format = '0.0'
            ws4.cell(row=r, column=14, value=x['gc_ms'])
            if x['impl'] == '병렬' and seq_avg:
                ws4.cell(row=r, column=15, value=seq_avg / x['elapsed_ns']).number_format = '0.00'
            r += 1
    box_rows(ws4, hdr4 + 1, r - 1, 15)
    for j, w_ in enumerate([6, 44, 6, 6, 8, 6, 12, 12, 11, 11, 13, 12, 12, 8, 11], start=1):
        ws4.column_dimensions[get_column_letter(j)].width = w_

    # ── 시트 5: 차트 ──
    ws5 = wb.create_sheet('차트')
    ws5['A1'] = ("첫 그래프의 원천은 종합 시트(구간 평균 클럭)이고, 지표별 그래프의 원천은 차트 데이터 시트다. "
                 "가로축은 모두 분할 개수다.")
    ws5['A1'].font = font(10)

    lc = LineChart()
    lc.title = '분할별 구간 평균 클럭 (순차 · 병렬도 6 · 병렬도 12)'
    lc.height, lc.width = 10, 26
    data_last = hdr + len(splits)
    lc.add_data(Reference(ws, min_col=4, max_col=6, min_row=hdr, max_row=data_last), titles_from_data=True)
    lc.set_categories(Reference(ws, min_col=1, min_row=hdr + 1, max_row=data_last))
    lc.y_axis.title = '클럭(GHz)'
    lc.x_axis.title = '분할 개수'
    lc.y_axis.scaling.min, lc.y_axis.scaling.max = 3.9, 4.4
    lc.y_axis.majorUnit = 0.1
    lc.y_axis.number_format = '0.0'
    lc.y_axis.delete = False
    lc.x_axis.delete = False
    lc.legend.position = 'r'
    lc.legend.overlay = False
    ws5.add_chart(lc, 'A3')

    # ── 시트: 차트 데이터 (지표별 그래프의 원천 표) ──
    metrics = [
        ('elapsed(ms)', 'elapsed_ns', 'ms'),
        ('procCPU(ms)', 'proc_ns', 'ms'),
        ('busyCore(개)', None, '개'),
        ('mainCPU(ms)', 'main_ns', 'ms'),
        ('workerCPU(ms)', 'worker_ns', 'ms'),
        ('기타 자바(ms)', 'other_ns', 'ms'),
        ('자바 아닌(ms)', 'nonjava_ns', 'ms'),
    ]
    ws_cd = wb.create_sheet('차트 데이터')
    ws_cd['A1'] = ('차트 시트 지표별 그래프의 원천 표다. 값은 분할마다 대표 실행의 병렬 측정 5회 평균(IMPLOG run 레코드)이고, '
                   'busyCore는 procCPU 평균을 elapsed 평균으로 나눈 값이다. run 레코드가 없는 분할은 빈 칸이다.')
    ws_cd['A1'].font = font(9)
    blocks_cd = []
    for mi, (mname, field, unit) in enumerate(metrics):
        c0 = 1 + mi * 4
        ws_cd.cell(row=3, column=c0, value=mname).font = font(10, True)
        for j, h in enumerate(['분할', '병렬도 6', '병렬도 12']):
            cell = ws_cd.cell(row=4, column=c0 + j, value=h)
            cell.font = font(9, True)
            cell.fill = hdr_fill
            cell.border = box
        rr = 5
        for sp in splits:
            e = by_split[sp]
            ws_cd.cell(row=rr, column=c0, value=sp)
            for j, par in enumerate((6, 12), start=1):
                rs = [x for x in e['runs'] if x['impl'] == '병렬' and x['par'] == par]
                if rs:
                    if field is None:
                        v = (sum(x['proc_ns'] for x in rs) / len(rs)) / (sum(x['elapsed_ns'] for x in rs) / len(rs))
                        fmt = '0.00'
                    else:
                        v = sum(x[field] for x in rs) / len(rs) / 1e6
                        fmt = '0.0'
                    ws_cd.cell(row=rr, column=c0 + j, value=v).number_format = fmt
            rr += 1
        for row in ws_cd.iter_rows(min_row=4, max_row=rr - 1, min_col=c0, max_col=c0 + 2):
            for c in row:
                c.border = box
                if c.font.name != AR:
                    c.font = font(9)
        blocks_cd.append((mname, unit, c0, rr - 1))
    for j in range(1, 1 + len(metrics) * 4):
        ws_cd.column_dimensions[get_column_letter(j)].width = 11

    # 지표별 그래프 — 가로축은 분할 개수, 선은 병렬도 6과 12
    anchor_row = 24
    for mname, unit, c0, last_row in blocks_cd:
        mc = LineChart()
        mc.title = f'분할별 {mname} (병렬도 6 · 병렬도 12, 병렬 5회 평균)'
        mc.height, mc.width = 9, 22
        mc.add_data(Reference(ws_cd, min_col=c0 + 1, max_col=c0 + 2, min_row=4, max_row=last_row), titles_from_data=True)
        mc.set_categories(Reference(ws_cd, min_col=c0, min_row=5, max_row=last_row))
        mc.y_axis.title = unit
        mc.x_axis.title = '분할 개수'
        mc.y_axis.delete = False
        mc.x_axis.delete = False
        mc.legend.position = 'r'
        mc.legend.overlay = False
        ws5.add_chart(mc, f'A{anchor_row}')
        anchor_row += 19

    try:
        wb.save(out_path)
    except PermissionError:
        raise SystemExit(f'출력 파일을 쓸 수 없다: {out_path}\n같은 이름의 엑셀 파일이 열려 있으면 닫고 다시 실행한다.')


def main():
    ap = argparse.ArgumentParser(description='분할별 클럭 원본 쌍을 전부 읽어 종합 분석 엑셀을 만든다')
    ap.add_argument('folder', nargs='?', default='.', help='원본 쌍이 있는 폴더 (기본: 현재 폴더)')
    ap.add_argument('-o', '--output', required=True, help='출력 xlsx 경로')
    ap.add_argument('--base', type=float, default=2.69, help='기본 클럭 GHz (기본값 2.69)')
    args = ap.parse_args()

    entries, by_split = collect(args.folder)
    build(entries, by_split, args.base, args.output)
    print(f'실행 {len(entries)}개, 분할 {len(by_split)}점 → {args.output}')
    for sp in sorted(by_split):
        e = by_split[sp]
        w = e['windows']
        def g(k):
            s = w.get(k)
            return f"{s['avg'] * args.base / 100:.2f}" if s else '-'
        print(f"  분할 {sp:>2}: 순차 {g('순차')} / 병렬도6 {g(('병렬', 6))} / 병렬도12 {g(('병렬', 12))} GHz  ({e['stem']})")


if __name__ == '__main__':
    main()
