import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';

export interface Job {
  id: number;
  company: string;
  title: string;
  location?: string;
  applyUrl?: string;
  postedAt?: string;
  firstSeenAt: string;
  dateIsReported: boolean;
  state: string;
  reposted: boolean;
  cvGenerated: boolean;
  applicationStatus?: string;
  fitScore?: number;
  chanceScore?: number;
  reason?: string;
  yearsRequired?: number;
  germanLevel?: string;
  matchState?: string;
  sources: string[];
}

export interface SearchTerm {
  id: number;
  text: string;
  term_group: string;
  kind: string;
  active: boolean;
}

export interface Company {
  id: number;
  name: string;
  category?: string;
  priority: string;
  ats_type: string;
  ats_tenant?: string;
  status: string;
  origin: string;
  detection_confidence: string;
  open_jobs: number;
  open_jobs_de: number;
  state: string;
  careers_url?: string;
  last_success_at?: string;
  last_job_count?: number;
}

export interface Unconfirmed {
  id: number;
  name: string;
  ats_type: string;
  ats_tenant: string;
  detection_evidence?: string;
}

export interface ScoreRun {
  considered: number;
  filteredOut: number;
  scored: number;
  failed: number;
  costUsd: number;
  stoppedBecause?: string;
}

export interface ScanSummary {
  companiesScanned: number;
  jobsFound: number;
  jobsCreated: number;
  failures: number;
  details: { company: string; source: string; found: number; created: number; error?: string }[];
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  constructor(private http: HttpClient) {}

  /** One page of jobs plus how many matched in all (the X-Total-Count header). */
  jobs(params: Record<string, any>): Observable<{ jobs: Job[]; total: number }> {
    return this.http.get<Job[]>('/api/v1/jobs', { params, observe: 'response' }).pipe(
      map((r) => {
        const jobs = r.body ?? [];
        const total = Number(r.headers.get('X-Total-Count'));
        return { jobs, total: Number.isFinite(total) && total > 0 ? total : jobs.length };
      }),
    );
  }

  /** One click: pull jobs, then score whatever is new. */
  scan(companyId?: number): Observable<{ scan: ScanSummary; scoring?: ScoreRun }> {
    const params: any = {};
    if (companyId) params.companyId = companyId;
    return this.http.post<{ scan: ScanSummary; scoring?: ScoreRun }>('/api/v1/scan', {}, { params });
  }

  terms(): Observable<SearchTerm[]> {
    return this.http.get<SearchTerm[]>('/api/v1/search-terms');
  }

  addTerm(text: string, kind = 'INCLUDE') {
    return this.http.post('/api/v1/search-terms', { text, kind, group: 'Custom' });
  }

  updateTerm(id: number, body: any) {
    return this.http.patch(`/api/v1/search-terms/${id}`, body);
  }

  setTermsActive(ids: number[], active: boolean) {
    return this.http.post('/api/v1/search-terms/active', { ids, active });
  }

  deleteTerm(id: number) {
    return this.http.delete(`/api/v1/search-terms/${id}`);
  }

  companies(scope: string = 'watchlist'): Observable<Company[]> {
    return this.http.get<Company[]>('/api/v1/companies', { params: { scope } });
  }

  /** Every job from one company, with the tag bar ignored. */
  companyJobs(id: number, germanyOnly: boolean): Observable<Job[]> {
    return this.http.get<Job[]>(`/api/v1/companies/${id}/jobs`, {
      params: { germanyOnly, includeFiltered: true },
    });
  }

  unconfirmed(): Observable<Unconfirmed[]> {
    return this.http.get<Unconfirmed[]>('/api/v1/companies/unconfirmed');
  }

  confirm(id: number) {
    return this.http.post(`/api/v1/companies/${id}/confirm`, {});
  }

  reject(id: number) {
    return this.http.post(`/api/v1/companies/${id}/reject`, {});
  }

  /** The posting text as the source gave it, fetched only when the user asks for it. */
  jobDescription(id: number): Observable<any> {
    return this.http.get(`/api/v1/jobs/${id}/description`);
  }

  patchJob(id: number, body: any) {
    return this.http.patch(`/api/v1/jobs/${id}`, body);
  }

  applications(): Observable<Application[]> {
    return this.http.get<Application[]>('/api/v1/applications');
  }

  gmailStatus(): Observable<GmailStatus> {
    return this.http.get<GmailStatus>('/api/v1/gmail/status');
  }

  gmailAuthUrl(): Observable<{ ok: boolean; url?: string; message?: string }> {
    return this.http.get<{ ok: boolean; url?: string; message?: string }>('/api/v1/gmail/auth-url');
  }

  gmailSync(): Observable<MailSync> {
    return this.http.post<MailSync>('/api/v1/gmail/sync', {});
  }

  gmailDisconnect() {
    return this.http.post('/api/v1/gmail/disconnect', {});
  }

  scans(): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/scans');
  }

  scanBoards(): Observable<any> {
    return this.http.post('/api/v1/scan-boards', {});
  }

  health(): Observable<any> {
    return this.http.get('/api/v1/health');
  }

  /** Per-source verdicts, failing boards and registry coverage. */
  sources(): Observable<any> {
    return this.http.get('/api/v1/sources');
  }
}

export interface Application {
  id: number;
  company: string;
  title?: string;        // never recorded for backfilled history
  source: string;        // CV_TOOL | CV_HISTORY | MANUAL
  folder?: string;
  status?: string;
  apply_url?: string;
  applied_at?: string;
  response_status: string;
  response_at?: string;
  response_subject?: string;
  response_snippet?: string;
  response_checked_at?: string;
  application_status?: string;
}

export interface GmailStatus {
  provider: string;
  credentialsConfigured: boolean;
  connected: boolean;
  imapConfigured?: boolean;
  oauthConfigured?: boolean;
  email?: string;
  last_sync_at?: string;
  last_error?: string;
}

export interface MailSync {
  connected: boolean;
  applicationsChecked: number;
  messagesFound: number;
  statusesUpdated: number;
  unclear: number;
  error?: string;
}
