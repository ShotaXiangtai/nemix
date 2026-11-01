'use strict';

(function () {
  const dict = window.NEMIX_I18N || {};
  const langSelect = document.getElementById('langSelect');

  function detectInitialLang() {
    try {
      const saved = localStorage.getItem('nemix_site_lang');
      if (saved && dict[saved]) return saved;
    } catch (_) {}

    const nav = (navigator.language || 'ja').toLowerCase();
    if (nav.startsWith('ja')) return 'ja';
    if (nav === 'zh-tw' || nav === 'zh-hk' || nav === 'zh-hant') return 'zh-TW';
    if (nav.startsWith('zh')) return 'zh-CN';
    if (nav.startsWith('ko')) return 'ko';
    if (nav.startsWith('es')) return 'es';
    return 'en';
  }

  function applyLanguage(langCode) {
    const lang = dict[langCode] ? langCode : 'ja';
    const table = dict[lang];
    document.documentElement.lang = lang;
    if (langSelect) langSelect.value = lang;

    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const key = el.getAttribute('data-i18n');
      if (table && table[key]) {
        if (key === 'hero_title') {
          el.innerHTML = table[key];
        } else {
          el.textContent = table[key];
        }
      }
    });

    try {
      localStorage.setItem('nemix_site_lang', lang);
    } catch (_) {}
  }

  if (langSelect) {
    langSelect.addEventListener('change', (e) => {
      applyLanguage(e.target.value);
    });
  }

  // Interactive P2P Group Style Switcher (Message Stream <-> Topic Channels)
  const streamBtn = document.getElementById('previewStreamBtn');
  const channelBtn = document.getElementById('previewChannelBtn');
  const streamView = document.getElementById('previewStreamView');
  const channelView = document.getElementById('previewChannelView');

  if (streamBtn && channelBtn && streamView && channelView) {
    streamBtn.addEventListener('click', () => {
      streamBtn.classList.add('active');
      channelBtn.classList.remove('active');
      streamView.classList.remove('hidden');
      channelView.classList.add('hidden');
    });

    channelBtn.addEventListener('click', () => {
      channelBtn.classList.add('active');
      streamBtn.classList.remove('active');
      channelView.classList.remove('hidden');
      streamView.classList.add('hidden');
    });
  }

  applyLanguage(detectInitialLang());
})();

