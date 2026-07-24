// components/progress-bar.js
class ProgressBarComponent {
    constructor(options = {}) {
        this.currentPhase = options.currentPhase || 'upload'; // upload, maps, code, deploy, test
        this.progressPercent = options.progressPercent || 5;
        this.onProgressClick = options.onProgressClick || null;

        // Updated step order: Upload → Maps → Code → Deploy → Test
        this.steps = [
            { id: 'upload', label: 'Upload' },
            { id: 'maps', label: 'Maps' },
            { id: 'code', label: 'Code' },
            { id: 'deploy', label: 'Deploy' },
            { id: 'test', label: 'Test' }
        ];

        this.phaseLabels = {
            upload: 'Upload Phase',
            maps: 'Maps Generation',
            code: 'Code Generation',
            deploy: 'Deployment Phase',
            test: 'Testing Phase'
        };
    }

    render() {
        const currentStepIndex = this.steps.findIndex(s => s.id === this.currentPhase);

        const stepsHTML = this.steps.map((step, index) => {
            let dotClass = '';
            let labelClass = '';

            if (index < currentStepIndex) {
                dotClass = 'completed';
            } else if (index === currentStepIndex) {
                dotClass = 'active';
                labelClass = 'active';
            }

            return `
                <div class="step-indicator">
                    <div class="step-dot ${dotClass}" id="step-${step.id}"></div>
                    <div class="step-label ${labelClass}">${step.label}</div>
                </div>
            `;
        }).join('');

        return `
            <div class="progress-section" id="progressSection">
                <div class="progress-header">
                    <span class="progress-label">
                        <span class="progress-phase-icon"></span>
                        <span id="globalPhaseLabel">${this.phaseLabels[this.currentPhase] || 'Upload Phase'}</span>
                    </span>
                    <span class="progress-value" id="globalProgressPercent">${this.progressPercent}%</span>
                </div>
                <div class="progress-bar">
                    <div class="progress-fill" id="globalProgressFill" style="width:${this.progressPercent}%;"></div>
                </div>
                <div class="progress-steps">
                    ${stepsHTML}
                </div>
            </div>
        `;
    }

    init(container) {
        container.innerHTML = this.render();
        this.bindEvents();
    }

    bindEvents() {
        const progressSection = document.getElementById('progressSection');
        if (progressSection && this.onProgressClick) {
            progressSection.addEventListener('click', this.onProgressClick);
        }
    }

    updateProgress(percent, phase) {
        this.progressPercent = percent;
        this.currentPhase = phase;

        // Update fill
        const fill = document.getElementById('globalProgressFill');
        if (fill) fill.style.width = percent + '%';

        // Update percent text
        const percentEl = document.getElementById('globalProgressPercent');
        if (percentEl) percentEl.textContent = percent + '%';

        // Update phase label
        const labelEl = document.getElementById('globalPhaseLabel');
        if (labelEl) labelEl.textContent = this.phaseLabels[phase] || phase;

        // Update step dots
        const currentStepIndex = this.steps.findIndex(s => s.id === phase);
        this.steps.forEach((step, index) => {
            const dot = document.getElementById('step-' + step.id);
            const label = dot ? dot.parentElement.querySelector('.step-label') : null;

            if (dot) {
                dot.classList.remove('active', 'completed');
                if (index < currentStepIndex) dot.classList.add('completed');
                if (index === currentStepIndex) dot.classList.add('active');
            }

            if (label) {
                label.classList.remove('active');
                if (index === currentStepIndex) label.classList.add('active');
            }
        });
    }
}

// Export for module use
if (typeof module !== 'undefined' && module.exports) {
    module.exports = ProgressBarComponent;
}