(function () {
  var root = document.documentElement;
  var KEY = 'tapflow-lang';

  function stored() {
    try { return localStorage.getItem(KEY); } catch (e) { return null; }
  }
  function save(v) {
    try { localStorage.setItem(KEY, v); } catch (e) { /* ignore */ }
  }
  function setLang(l) {
    root.setAttribute('data-lang', l);
    root.lang = l;
    var b = document.getElementById('langBtn');
    if (b) b.textContent = l === 'vi' ? 'EN' : 'VI';
  }

  var q = new URLSearchParams(location.search).get('lang');
  var initial = (q === 'vi' || q === 'en') ? q
    : stored() || ((navigator.language || 'en').toLowerCase().indexOf('vi') === 0 ? 'vi' : 'en');
  setLang(initial);

  var btn = document.getElementById('langBtn');
  if (btn) btn.addEventListener('click', function () {
    var next = root.getAttribute('data-lang') === 'vi' ? 'en' : 'vi';
    setLang(next);
    save(next);
  });

  var y = document.getElementById('year');
  if (y) y.textContent = new Date().getFullYear();
})();
