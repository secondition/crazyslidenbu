(function (account) {
    if (window.activityResultObserver) return;
    let finished = false;
    function report() {
        if (finished) return;
        const status = document.querySelector('#app p[uno-font="bold"]');
        const notification = document.querySelector('.van-notify--danger');
        let message = '';
        let success = false;
        if (status) {
            const color = status.getAttribute('uno-text');
            if (color !== '16px green' && color !== '16px red') return;
            message = status.textContent.trim();
            success = color === '16px green';
        } else if (notification && notification.getClientRects().length) {
            message = notification.textContent.trim();
        }
        if (!message) return;
        finished = true;
        window.activityResultObserver.disconnect();
        SignResult.complete(account, success, message);
    }
    window.activityResultObserver = new MutationObserver(report);
    window.activityResultObserver.observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true
    });
    report();
})
