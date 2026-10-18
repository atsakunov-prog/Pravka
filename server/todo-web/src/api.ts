// Сеть: тот же API, что у телефона, прежнего веба и Claude. Кука входа + заголовок X-Dela: без него сервер не примет
// запрос, который что-то меняет (чужая страница его не подставит).

export class ApiError extends Error {
  status: number;
  constructor(msg: string, status: number) {
    super(msg);
    this.status = status;
  }
}

export async function api<T = any>(path: string, body?: unknown): Promise<T> {
  const opt: RequestInit = { credentials: 'same-origin', headers: { 'X-Dela': '1' } };
  if (body !== undefined) {
    opt.method = 'POST';
    (opt.headers as Record<string, string>)['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(body);
  }
  let r: Response;
  try {
    r = await fetch(path, opt);
  } catch {
    throw new ApiError('нет связи с сервером', 0);
  }
  let d: any = {};
  try {
    d = await r.json();
  } catch {
    /* пусто */
  }
  if (r.status === 401) throw new ApiError('нужен вход', 401);
  if (!r.ok || d.ok === false) {
    throw new ApiError(d.error || (r.status === 404 ? 'сервер не знает этот адрес (404)' : 'ошибка ' + r.status), r.status);
  }
  return d as T;
}

export const uuid = (): string =>
  typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => ((Math.random() * 16) | 0).toString(16));

/** Фоновое задание сервера (Claude): номер — и опрос, пока думает. */
export async function runJob<T = any>(path: string, body: unknown): Promise<T> {
  const { job } = await api<{ job: string }>(path, body);
  for (let i = 0; ; i++) {
    await new Promise((r) => setTimeout(r, i < 15 ? 800 : 2500));
    const d = await api<any>(path + '/' + job);
    if (d.status !== 'run') {
      if (d.status === 'error') throw new ApiError(d.error || 'Claude не справился', 500);
      return d as T;
    }
    if (i > 150) throw new ApiError('Claude думает дольше пяти минут — всё появится само, когда он закончит', 504);
  }
}
