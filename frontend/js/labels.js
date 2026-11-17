(function () {
  let idCounter = 1;
  function linkAllLabels() {
    const controls = document.querySelectorAll('input, select, textarea');
    controls.forEach(function (ctrl) {
      let label = ctrl.closest('label');
      if (!label && ctrl.id) {
        label = document.querySelector('label[for="' + ctrl.id + '"]');
      }
      if (label) {
        if (!ctrl.id) {
          ctrl.id = 'ctrl-auto-' + (idCounter++);
        }
        if (label.htmlFor !== ctrl.id) {
          label.htmlFor = ctrl.id;
        }
      }
    });
  }
  const observer = new MutationObserver(linkAllLabels);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  window.addEventListener('DOMContentLoaded', linkAllLabels);
})();
