// components/navbar.js
class NavbarComponent {
    constructor(options = {}) {
        this.activePage = options.activePage || 'pahal';
        this.workspaceName = options.workspaceName || 'Project Mercury';
        this.userName = options.userName || 'Arjun Kumar';
        this.userInitials = options.userInitials || 'AK';
        this.onWorkspaceClick = options.onWorkspaceClick || null;
        this.onUserClick = options.onUserClick || null;
        this.onAssetsClick = options.onAssetsClick || null;
    }

    render() {
        const navItems = [
            { id: 'kaveri', label: 'Kaveri', icon: 'fa-code', href: 'kaveri.html' },
            { id: 'ganga', label: 'Ganga', icon: 'fa-chart-line', href: 'ganga.html' },
            { id: 'yamuna', label: 'Yamuna', icon: 'fa-flask', href: 'yamuna.html' },
            { id: 'sangam', label: 'Sangam', icon: 'fa-rocket', href: 'sangam.html' },
            { id: 'chatAgent', label: 'ChatAgent', icon: 'fa-message-smile', href: 'chatAgent.html' },
            { id: 'assets', label: 'Assets', icon: 'fa-box-archive', href: '#', isAssets: true }
        ];

        const navLinks = navItems.map(item => {
            if (item.isAssets) {
                return `<a href="#" class="nav-link" data-nav="assets" id="assetsNavLink">
                    <i class="fas ${item.icon} nav-icon"></i><span>${item.label}</span>
                </a>`;
            }
            return `<a href="${item.href}" class="nav-link ${item.id === this.activePage ? 'active' : ''}" data-nav="${item.id}">
                <i class="fas ${item.icon} nav-icon"></i><span>${item.label}</span>
            </a>`;
        }).join('');

        return `
            <nav class="navbar" id="navbar">
                <a href="pahal.html" class="navbar-brand">
                    <div class="navbar-logo">P</div>
                    <span class="navbar-name">PahAl</span>
                </a>
                <div class="navbar-nav">
                    ${navLinks}
                </div>
                <div class="navbar-right">
                    <div class="workspace-btn" id="workspaceBtn">
                        <span class="workspace-dot"></span>
                        <span id="workspaceName">${this.workspaceName}</span>
                        <i class="fas fa-chevron-down" style="font-size:0.6rem;transition:transform 0.3s;"></i>
                    </div>
                    <div class="user-btn" id="userBtn">
                        <div class="user-avatar">${this.userInitials}</div>
                        <span class="user-name">${this.userName}</span>
                    </div>
                </div>
            </nav>
        `;
    }

    init(container) {
        container.innerHTML = this.render();
        this.bindEvents();
    }

    bindEvents() {
        const workspaceBtn = document.getElementById('workspaceBtn');
        if (workspaceBtn && this.onWorkspaceClick) {
            workspaceBtn.addEventListener('click', this.onWorkspaceClick);
        }

        const userBtn = document.getElementById('userBtn');
        if (userBtn && this.onUserClick) {
            userBtn.addEventListener('click', this.onUserClick);
        }

        // Assets nav link
        const assetsNavLink = document.getElementById('assetsNavLink');
        if (assetsNavLink && this.onAssetsClick) {
            assetsNavLink.addEventListener('click', (e) => {
                e.preventDefault();
                this.onAssetsClick();
            });
        }
    }

    updateWorkspace(name) {
        const el = document.getElementById('workspaceName');
        if (el) el.textContent = name;
    }
}