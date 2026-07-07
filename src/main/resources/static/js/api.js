/**
 * 공통 API 헬퍼.
 * 모든 화면이 이 파일의 함수로 서버 JSON API를 호출한다.
 * 인증은 로그인 시 발급된 Authorization HttpOnly 쿠키를 브라우저가 자동 전송하므로
 * 여기에는 인증 관련 코드가 없다.
 */

/** 공통 호출: 실패 응답(errorCode/message)을 Error로 변환해 던진다 */
async function api(path, options = {}) {
    const res = await fetch(path, options);
    const text = await res.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch (e) { /* JSON 아님 */ }

    if (!res.ok) {
        const msg = body ? (body.message || body.errorCode || JSON.stringify(body)) : ('HTTP ' + res.status);
        const err = new Error(msg);
        err.status = res.status;
        err.errorCode = body ? body.errorCode : null;
        throw err;
    }
    return body;
}

/** JSON 본문 POST */
function postJson(path, data) {
    return api(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
}

/** multipart POST: meta(JSON 파트) + photo(파일 파트) — 기준 측정·인증 제출용 */
function postMultipart(path, meta, photoFile) {
    const form = new FormData();
    form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
    form.append('photo', photoFile);
    return api(path, { method: 'POST', body: form });
}

/** 현재 로그인 사용자 (JWT 쿠키 기반). 미로그인·만료면 null */
async function currentUser() {
    try { return await api('members/loginSuccess'); } catch (e) { return null; }
}

/** URL 쿼리 파라미터 */
function qs(name) {
    return new URLSearchParams(location.search).get(name);
}

/** 상단 내비게이션 렌더링. 로그인 필수 화면은 requireLogin=true로 호출 */
async function renderNav(requireLogin) {
    const user = await currentUser();
    if (requireLogin && !user) {
        location.href = 'login.html';
        return null;
    }
    const nav = document.createElement('nav');
    nav.innerHTML = `
        <a href="index.html">챌린지 목록</a>
        <a href="challenge-create.html">챌린지 등록</a>
        <a href="deposits.html">예치</a>
        <span class="spacer"></span>
        ${user
            ? `<span class="user">${user.userName} (${user.userId})</span>
               <a href="member-edit.html">내 정보</a>
               <a href="#" id="nav-logout">로그아웃</a>`
            : `<a href="login.html">로그인</a> <a href="join.html">회원가입</a>`}
    `;
    document.body.prepend(nav);
    const logout = document.getElementById('nav-logout');
    if (logout) {
        logout.addEventListener('click', async (e) => {
            e.preventDefault();
            await fetch('logout', { method: 'POST' });
            location.href = 'login.html';
        });
    }
    return user;
}

/** 메시지 영역 출력 */
function showMsg(el, text, ok) {
    el.textContent = text;
    el.className = 'msg ' + (ok ? 'ok' : 'error');
}
