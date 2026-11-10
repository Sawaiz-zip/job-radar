import { Component, DestroyRef, HostListener, OnInit, signal, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, concatMap, of, switchMap, tap, timer } from 'rxjs';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, Application, Company, GmailStatus, Job, MailSync, ScanSummary, ScoreRun, SearchTerm, Unconfirmed } from './api.service';

type Tab = 'jobs' | 'applied' | 'companies' | 'confirm' | 'sources';

type Sort = 'date' | 'chance' | 'fit' | 'company';
type Freshness = '' | 'scan' | '24' | '72' | '168';
type Posted = '' | '24' | '72' | '168' | '720';

/**
 * Every control of the jobs view, in one value. It used to be six separate signals each
 * firing its own request, and responses arriving out of order made the list show the
 * result of an older click.
 */
export interface Filters {
  text: string;
  city: string;
  remoteOnly: boolean;
  sourceKind: '' | 'company' | 'board';
  minChance: number;
  minFit: number;
  maxYears: number | null;
  maxGerman: '' | 'A2' | 'B1' | 'B2';
  hideApplied: boolean;
  germanyOnly: boolean;
  includeFiltered: boolean;
  freshness: Freshness;
  posted: Posted;
  sort: Sort;
}

const DEFAULT_FILTERS: Filters = {
  text: '',
  city: '',
  remoteOnly: false,
  sourceKind: '',
  minChance: 0,
  minFit: 0,
  maxYears: null,
  maxGerman: '',
  hideApplied: false,
  germanyOnly: true,
  includeFiltered: false,
  freshness: '',
  posted: '',
  // Chance first: the whole point is where an application is actually worth writing.
  sort: 'chance',
};

const FILTERS_KEY = 'jobradar.filters.v3';
const OLD_FILTERS_KEY = 'jobradar.filters.v2';
const PAGE = 200;

function loadSavedFilters(): Filters {
  try {
    const raw = localStorage.getItem(FILTERS_KEY);
    if (raw) return { ...DEFAULT_FILTERS, ...JSON.parse(raw) };
    // v2 had one date filter that read as "posted" but measured "first seen". Carry an
    // hour window over to the new posted filter, which is what it looked like it did.
    const old = localStorage.getItem(OLD_FILTERS_KEY);
    if (!old) return { ...DEFAULT_FILTERS };
    const f = { ...DEFAULT_FILTERS, ...JSON.parse(old) } as Filters;
    if (f.freshness && f.freshness !== 'scan') {
      f.posted = f.freshness as Posted;
      f.freshness = '';
    }
    return f;
  } catch {
    return { ...DEFAULT_FILTERS };
  }
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent implements OnInit {
  tab = signal<Tab>('jobs');

  jobs = signal<Job[]>([]);
  terms = signal<SearchTerm[]>([]);
  companies = signal<Company[]>([]);
  unconfirmed = signal<Unconfirmed[]>([]);
  health = signal<any>(null);
  sources = signal<any>(null);
  applications = signal<Application[]>([]);
  gmail = signal<GmailStatus | null>(null);
  mailSync = signal<MailSync | null>(null);
  syncingMail = signal(false);

  loading = signal(false);
  scanning = signal(false);
  lastScan = signal<ScanSummary | null>(null);
  lastScoring = signal<ScoreRun | null>(null);
  syncingCompany = signal<number | null>(null);

  filters = signal<Filters>(loadSavedFilters());
  total = signal(0);
  limit = signal(PAGE);
  jobsError = signal('');
  showMore = signal(false);
  lastScanInfo = signal<any>(null);
  newTerm = signal('');

  private reload$ = new Subject<number>();
  private destroyRef = inject(DestroyRef);

  readonly sorts: { key: Sort; label: string }[] = [
    { key: 'chance', label: 'Best chance' },
    { key: 'fit', label: 'Best fit' },
    { key: 'date', label: 'Newest' },
    { key: 'company', label: 'Company' },
  ];

  // Two different questions, so two separate filters. "Posted" is the date printed on each
  // card (the employer's date, or first seen when the source gives none). "Found" is when
  // Job Radar first saw the job: an old posting at a newly detected company is found today
  // but was posted months ago, which is why one filter cannot answer both.
  readonly postedOptions: { key: Posted; label: string; title: string }[] = [
    { key: '', label: 'Any time', title: 'No posting-date limit' },
    { key: '24', label: '24h', title: 'Posted in the last 24 hours' },
    { key: '72', label: '3 days', title: 'Posted in the last 3 days' },
    { key: '168', label: '1 week', title: 'Posted in the last week' },
    { key: '720', label: '1 month', title: 'Posted in the last 30 days' },
  ];
  readonly freshnessOptions: { key: Freshness; label: string; title: string }[] = [
    { key: '', label: 'Any', title: 'No limit on when Job Radar found it' },
    { key: 'scan', label: 'Last scan', title: 'Only jobs the last scan found for the first time' },
    { key: '24', label: '24h', title: 'Found by Job Radar in the last 24 hours' },
    { key: '168', label: '1 week', title: 'Found by Job Radar in the last week' },
  ];

  /** The filters that differ from the defaults, each with a way to undo just that one. */
  activeFilters = computed(() => {
    const f = this.filters();
    const out: { key: keyof Filters; label: string }[] = [];
    if (f.text.trim()) out.push({ key: 'text', label: `"${f.text.trim()}"` });
    if (f.city.trim()) out.push({ key: 'city', label: `in ${f.city.trim()}` });
    if (f.remoteOnly) out.push({ key: 'remoteOnly', label: 'remote / hybrid' });
    if (f.sourceKind) out.push({ key: 'sourceKind', label: f.sourceKind === 'company' ? 'company boards' : 'job boards' });
    if (f.minChance) out.push({ key: 'minChance', label: `chance ≥ ${f.minChance}` });
    if (f.minFit) out.push({ key: 'minFit', label: `fit ≥ ${f.minFit}` });
    if (f.maxYears != null) out.push({ key: 'maxYears', label: `≤ ${f.maxYears} yrs asked` });
    if (f.maxGerman) out.push({ key: 'maxGerman', label: `German ≤ ${f.maxGerman}` });
    if (f.hideApplied) out.push({ key: 'hideApplied', label: 'not applied' });
    if (!f.germanyOnly) out.push({ key: 'germanyOnly', label: 'outside Germany too' });
    if (f.includeFiltered) out.push({ key: 'includeFiltered', label: 'with stretch roles' });
    if (f.posted) out.push({ key: 'posted', label: 'posted ' + this.postedOptions.find((o) => o.key === f.posted)!.label });
    if (f.freshness) out.push({ key: 'freshness', label: 'found ' + (this.freshnessOptions.find((o) => o.key === f.freshness)?.label ?? f.freshness) });
    return out;
  });

  /** How many of the "more filters" are set, shown on the button while the panel is shut. */
  hiddenFilterCount = computed(() => {
    const f = this.filters();
    return [f.city.trim(), f.remoteOnly, f.sourceKind, f.minChance, f.minFit,
            f.maxYears != null, f.maxGerman, f.hideApplied].filter(Boolean).length;
  });

  activeTerms = computed(() => this.terms().filter((t) => t.active && t.kind === 'INCLUDE'));

  includeTerms = computed(() =>
    this.terms()
      .filter((t) => t.kind === 'INCLUDE')
      .sort((a, b) => a.text.localeCompare(b.text)),
  );

  /** The tag bar as groups ("AI", "Java/backend (EN)", …), each switchable as one. */
  termGroups = computed(() => {
    const groups = new Map<string, SearchTerm[]>();
    for (const t of this.includeTerms()) {
      const name = (t.term_group || 'Custom').replace(/^Include\s*[–-]\s*/i, '');
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name)!.push(t);
    }
    return [...groups.entries()]
      .map(([name, terms]) => ({ name, terms, on: terms.filter((t) => t.active).length }))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  showTags = signal(this.readFlag('jobradar.showTags'));

  flagTerms = computed(() => this.terms().filter((t) => t.kind.startsWith('FLAG') || t.kind === 'DROP'));

  constructor(private api: ApiService) {}

  /** Companies with a live board, most recently synced first. */
  liveCompanies = computed(() =>
    this.companies().filter((c) => c.detection_confidence === 'CONFIRMED'),
  );

  ngOnInit(): void {
    // One request at a time: a newer filter change cancels the request still in flight,
    // so a slow old response can never overwrite the list for a newer click. Typing is
    // debounced; clicks are not.
    this.reload$
      .pipe(
        switchMap((delay) =>
          timer(delay).pipe(
            tap(() => {
              this.loading.set(true);
              this.jobsError.set('');
            }),
            switchMap(() =>
              this.api.jobs(this.jobParams()).pipe(
                catchError(() => {
                  this.jobsError.set('Could not reach the Job Radar API — is it running on :8090?');
                  return of(null);
                }),
              ),
            ),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => {
        this.loading.set(false);
        if (!r) return;
        this.jobs.set(r.jobs);
        this.total.set(r.total);
      });

    // Jobs must wait for the tags: the active tags are the filter, so loading both in
    // parallel would show an unfiltered list on first paint.
    this.loadTerms(() => this.loadJobs());
    this.refreshHealth();
    this.loadCompanies();
    this.loadScans();
  }

  companyScope = signal<'watchlist' | 'board' | 'all'>('watchlist');
  viewingCompany = signal<Company | null>(null);

  loadCompanies(): void {
    this.api.companies(this.companyScope()).subscribe((c) => this.companies.set(c));
  }

  loadScans(): void {
    this.api.scans().subscribe((s) => this.lastScanInfo.set(s?.[0] ?? null));
  }

  setCompanyScope(scope: 'watchlist' | 'board' | 'all'): void {
    this.companyScope.set(scope);
    this.loadCompanies();
  }

  /**
   * Everything one company has. The tags are ignored here (they answer "what should I
   * look at today", this answers "what does this employer have"), but the other filters
   * still apply, so changing one no longer throws you out of the company view.
   */
  showAllJobsFor(c: Company): void {
    this.viewingCompany.set(c);
    this.tab.set('jobs');
    this.reloadJobs();
  }

  clearCompanyView(): void {
    this.viewingCompany.set(null);
    this.reloadJobs();
  }

  syncCompany(c: Company, event: MouseEvent): void {
    event.stopPropagation();
    this.syncingCompany.set(c.id);
    this.api.scan(c.id).subscribe({
      next: (r) => {
        this.syncingCompany.set(null);
        this.lastScan.set(r.scan);
        this.lastScoring.set(r.scoring ?? null);
        this.loadCompanies();
        this.loadJobs();
        this.refreshHealth();
      },
      error: () => this.syncingCompany.set(null),
    });
  }

  filterToCompany(c: Company): void {
    this.showAllJobsFor(c);
  }

  since(iso?: string): string {
    if (!iso) return 'never';
    const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return hrs + 'h ago';
    return Math.floor(hrs / 24) + 'd ago';
  }

  loadTerms(then?: () => void): void {
    this.api.terms().subscribe({
      next: (t) => {
        this.terms.set(t);
        then?.();
      },
      // Without the tags there is no filter to apply, and an empty list would read as
      // "no jobs match" when the truth is that the API is not answering.
      error: () => this.jobsError.set('Could not reach the Job Radar API — is it running on :8090?'),
    });
  }

  /** Try again after the API came back. */
  retry(): void {
    this.jobsError.set('');
    this.loadTerms(() => this.reloadJobs());
    this.refreshHealth();
    this.loadCompanies();
    this.loadScans();
  }

  /** Ask for the list again; {@code debounceMs} is for typing, clicks reload at once. */
  reloadJobs(debounceMs = 0): void {
    this.limit.set(PAGE);
    this.reload$.next(debounceMs);
  }

  /** Kept for the scan and sync handlers: refresh the list with the current filters. */
  loadJobs(): void {
    this.reload$.next(0);
  }

  loadMore(): void {
    if (this.loading() || this.total() <= this.jobs().length) return;
    this.limit.update((l) => l + PAGE);
    this.reload$.next(0);
  }

  /** Load the next page on its own when the list is scrolled near its end. */
  @HostListener('window:scroll')
  onScroll(): void {
    if (this.tab() !== 'jobs') return;
    const nearBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 800;
    if (nearBottom) this.loadMore();
  }

  private jobParams(): Record<string, any> {
    const f = this.filters();
    const company = this.viewingCompany();
    const params: Record<string, any> = {
      sort: f.sort,
      germanyOnly: f.germanyOnly,
      includeFiltered: company ? true : f.includeFiltered,
      hideApplied: f.hideApplied,
      limit: this.limit(),
    };
    if (company) params['companyId'] = company.id;
    if (f.freshness) params['freshness'] = f.freshness;
    if (f.posted) params['postedWithinHours'] = f.posted;
    if (f.text.trim()) params['text'] = f.text.trim();
    if (f.city.trim()) params['city'] = f.city.trim();
    if (f.remoteOnly) params['remoteOnly'] = true;
    if (f.sourceKind) params['sourceKind'] = f.sourceKind;
    if (f.minChance) params['minChance'] = f.minChance;
    if (f.minFit) params['minFit'] = f.minFit;
    if (f.maxYears != null) params['maxYears'] = f.maxYears;
    if (f.maxGerman) params['maxGerman'] = f.maxGerman;
    // Tags and typed text now combine: tags pick the kind of role, text narrows within it.
    const active = this.activeTerms().map((t) => t.text);
    if (!company && active.length) params['term'] = active;
    return params;
  }

  /** Change one filter, remember it across reloads, and refresh the list. */
  setFilter<K extends keyof Filters>(key: K, value: Filters[K], debounceMs = 0): void {
    this.filters.update((f) => ({ ...f, [key]: value }));
    try {
      localStorage.setItem(FILTERS_KEY, JSON.stringify(this.filters()));
    } catch {
      /* private window: filters just will not survive a reload */
    }
    this.reloadJobs(debounceMs);
  }

  resetFilter(key: keyof Filters): void {
    this.setFilter(key, DEFAULT_FILTERS[key] as any);
  }

  /** Every filter back to its default. The tags are left alone; they have their own "clear". */
  resetAllFilters(): void {
    this.filters.set({ ...DEFAULT_FILTERS, sort: this.filters().sort });
    try {
      localStorage.removeItem(FILTERS_KEY);
    } catch {
      /* ignore */
    }
    this.reloadJobs();
  }

  numberOrNull(v: any): number | null {
    return v === '' || v == null ? null : Number(v);
  }

  refreshHealth(): void {
    this.api.health().subscribe((h) => this.health.set(h));
  }

  scan(): void {
    this.scanning.set(true);
    this.lastScan.set(null);
    this.api.scan().subscribe({
      next: (r) => {
        this.lastScan.set(r.scan);
        this.lastScoring.set(r.scoring ?? null);
        this.scanning.set(false);
        this.loadJobs();
        this.loadCompanies();
        this.refreshHealth();
        this.loadScans();
      },
      error: () => this.scanning.set(false),
    });
  }

  /**
   * Server writes for tags run one after another, in click order. Two quick clicks on
   * the same tag used to send two PATCHes that could arrive in either order, leaving
   * the server holding the opposite of what the screen showed after a reload.
   */
  private termWrites$ = new Subject<() => Observable<unknown>>();
  private termWritesStarted = false;

  private writeTerms(ids: number[], active: boolean, rollback: () => void): void {
    if (!this.termWritesStarted) {
      this.termWritesStarted = true;
      this.termWrites$
        .pipe(concatMap((w) => w()), takeUntilDestroyed(this.destroyRef))
        .subscribe();
    }
    this.termWrites$.next(() =>
      this.api.setTermsActive(ids, active).pipe(
        catchError(() => {
          rollback();
          this.reloadJobs();
          return of(null);
        }),
      ),
    );
  }

  private setTermsLocally(ids: number[], active: boolean): void {
    const set = new Set(ids);
    this.terms.update((all) => all.map((t) => (set.has(t.id) ? { ...t, active } : t)));
  }

  /** Flip one tag. The list reloads from local state at once; the server just remembers. */
  toggleTerm(term: SearchTerm): void {
    const next = !term.active;
    this.setTermsLocally([term.id], next);
    this.reloadJobs();
    this.writeTerms([term.id], next, () => this.setTermsLocally([term.id], !next));
  }

  /** A group pill: all on if any is off, otherwise all off. */
  toggleGroup(group: { terms: SearchTerm[]; on: number }): void {
    const next = group.on < group.terms.length;
    const before = group.terms.map((t) => ({ id: t.id, active: t.active }));
    const ids = group.terms.map((t) => t.id);
    this.setTermsLocally(ids, next);
    this.reloadJobs();
    this.writeTerms(ids, next, () => before.forEach((b) => this.setTermsLocally([b.id], b.active)));
  }

  /** Every tag on or off. */
  setAllTags(active: boolean): void {
    const terms = this.includeTerms().filter((t) => t.active !== active);
    if (!terms.length) return;
    const ids = terms.map((t) => t.id);
    this.setTermsLocally(ids, active);
    this.reloadJobs();
    this.writeTerms(ids, active, () => this.setTermsLocally(ids, !active));
  }

  toggleShowTags(): void {
    this.showTags.set(!this.showTags());
    try {
      localStorage.setItem('jobradar.showTags', this.showTags() ? '1' : '');
    } catch {
      /* ignore */
    }
  }

  private readFlag(key: string): boolean {
    try {
      return !!localStorage.getItem(key);
    } catch {
      return false;
    }
  }

  addTerm(): void {
    const text = this.newTerm().trim();
    if (!text) return;
    this.api.addTerm(text).subscribe(() => {
      this.newTerm.set('');
      this.api.terms().subscribe((t) => {
        this.terms.set(t);
        this.reloadJobs();
      });
    });
  }

  removeTerm(term: SearchTerm, event: MouseEvent): void {
    event.stopPropagation();
    this.api.deleteTerm(term.id).subscribe(() => {
      this.terms.update((all) => all.filter((t) => t.id !== term.id));
      this.reloadJobs();
    });
  }

  copiedJob = signal<number | null>(null);
  copyError = signal<string>('');

  /**
   * Copy the posting for pasting straight into Job Tailor. The text comes from the
   * source's own API, so it has none of the navigation and "similar jobs" padding a
   * scrape of the board's page picks up.
   */
  copyDescription(job: Job, event: MouseEvent): void {
    event.stopPropagation();
    this.copyError.set('');
    this.api.jobDescription(job.id).subscribe({
      next: (r) => {
        if (!r?.ok || !r.description_text) {
          this.copyError.set('No description stored for this job — open it instead.');
          return;
        }
        const header = [
          r.title,
          r.company,
          r.location_raw,
          r.apply_url,
        ].filter(Boolean).join('\n');
        const text = `${header}\n\n${r.description_text}`;

        navigator.clipboard.writeText(text).then(
          () => {
            this.copiedJob.set(job.id);
            setTimeout(() => this.copiedJob.set(null), 2000);
          },
          () => this.copyError.set('The browser blocked clipboard access.'),
        );
      },
      error: () => this.copyError.set('Could not load the description.'),
    });
  }

  dismiss(job: Job): void {
    this.api.patchJob(job.id, { dismissed: true }).subscribe(() => {
      this.jobs.update((all) => all.filter((j) => j.id !== job.id));
      this.total.update((n) => Math.max(0, n - 1));
    });
  }

  markApplied(job: Job): void {
    this.api.patchJob(job.id, { applicationStatus: 'Applied' }).subscribe(() => {
      if (this.filters().hideApplied) {
        this.jobs.update((all) => all.filter((j) => j.id !== job.id));
        this.total.update((n) => Math.max(0, n - 1));
        return;
      }
      this.jobs.update((all) =>
        all.map((j) => (j.id === job.id ? { ...j, applicationStatus: 'Applied' } : j)),
      );
    });
  }

  openTab(tab: Tab): void {
    this.tab.set(tab);
    if (tab === 'applied') {
      this.api.applications().subscribe((a) => this.applications.set(a));
      this.api.gmailStatus().subscribe((g) => this.gmail.set(g));
    }
    if (tab === 'companies') this.loadCompanies();
    if (tab === 'confirm') this.api.unconfirmed().subscribe((u) => this.unconfirmed.set(u));
    if (tab === 'sources') this.api.sources().subscribe((x) => this.sources.set(x));
  }

  confirmBoard(c: Unconfirmed): void {
    this.api.confirm(c.id).subscribe(() => {
      this.unconfirmed.update((all) => all.filter((x) => x.id !== c.id));
      this.refreshHealth();
    });
  }

  rejectBoard(c: Unconfirmed): void {
    this.api.reject(c.id).subscribe(() => {
      this.unconfirmed.update((all) => all.filter((x) => x.id !== c.id));
      this.refreshHealth();
    });
  }

  connectGmail(): void {
    this.api.gmailAuthUrl().subscribe((r) => {
      if (r.ok && r.url) {
        // Consent happens in the user's own browser; Job Radar only ever sees the code.
        window.open(r.url, '_blank');
      } else {
        this.mailSync.set({
          connected: false, applicationsChecked: 0, messagesFound: 0,
          statusesUpdated: 0, unclear: 0,
          error: r.message ?? 'Could not build the Google consent link',
        });
      }
    });
  }

  refreshGmail(): void {
    this.api.gmailStatus().subscribe((g) => this.gmail.set(g));
  }

  syncMail(): void {
    this.syncingMail.set(true);
    this.api.gmailSync().subscribe({
      next: (r) => {
        this.mailSync.set(r);
        this.syncingMail.set(false);
        this.api.applications().subscribe((a) => this.applications.set(a));
        this.refreshGmail();
      },
      error: () => this.syncingMail.set(false),
    });
  }

  disconnectGmail(): void {
    this.api.gmailDisconnect().subscribe(() => this.refreshGmail());
  }

  scanBoards(): void {
    this.scanning.set(true);
    this.api.scanBoards().subscribe({
      next: (r) => {
        this.scanning.set(false);
        this.lastScoring.set(r.scoring ?? null);
        this.loadJobs();
        this.refreshHealth();
      },
      error: () => this.scanning.set(false),
    });
  }

  /** What to show for an application whose status we could not determine. */
  statusLabel(a: Application): string {
    switch (a.response_status) {
      case 'REJECTED': return 'Rejected';
      case 'INTERVIEW': return 'Interview';
      case 'OFFER': return 'Offer';
      case 'ACKNOWLEDGED': return 'Acknowledged';
      case 'UNCLEAR': return 'Reply — unclear';
      default:
        if (!this.gmail()?.connected) return 'No mailbox connected';
        return a.response_checked_at ? 'No reply yet' : 'Not checked';
    }
  }

  statusClass(a: Application): string {
    if (!this.gmail()?.connected && a.response_status === 'NONE') return 'unknown';
    return a.response_status.toLowerCase();
  }

  boardUrl(c: Unconfirmed): string {
    const t = c.ats_tenant;
    switch (c.ats_type) {
      case 'GREENHOUSE': return `https://job-boards.greenhouse.io/${t}`;
      case 'LEVER': return `https://jobs.lever.co/${t}`;
      case 'ASHBY': return `https://jobs.ashbyhq.com/${t}`;
      case 'PERSONIO': return `https://${t}.jobs.personio.de`;
      case 'RECRUITEE': return `https://${t}.recruitee.com`;
      default: return '#';
    }
  }

  /** Days since the job was posted, or since we first saw it. */
  age(job: Job): string {
    const iso = job.postedAt ?? job.firstSeenAt;
    const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000);
    if (days <= 0) return 'today';
    if (days === 1) return 'yesterday';
    if (days < 31) return `${days}d ago`;
    return `${Math.floor(days / 30)}mo ago`;
  }

  isFresh(job: Job): boolean {
    const iso = job.postedAt ?? job.firstSeenAt;
    return Date.now() - new Date(iso).getTime() < 3 * 86400000;
  }
}
