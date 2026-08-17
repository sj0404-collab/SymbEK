import { useEffect, useMemo, useState } from "react";

function native() {
  return window.KenjiSpace || window.Symbiosis || window.Kenji || null;
}

function call(name, ...args) {
  try {
    const n = native();
    if (!n || typeof n[name] !== "function") return null;
    return n[name](...args);
  } catch {
    return null;
  }
}

function parse(raw, fallback) {
  try {
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

export default function App() {
  const [games, setGames] = useState([]);
  const [status, setStatus] = useState({ items: [], dataRoot: "" });
  const [query, setQuery] = useState("");
  const [note, setNote] = useState("");
  const ready = !!native();

  const reload = () => {
    setGames(parse(call("games"), { games: [] }).games || []);
    setStatus(parse(call("status"), { items: [], dataRoot: "" }));
  };

  useEffect(() => {
    reload();
    const id = setInterval(reload, 2500);
    window.onFolderAdded = reload;
    window.onSavesPicked = reload;
    return () => clearInterval(id);
  }, []);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return games.filter((g) => {
      if (!q) return true;
      return [g.title, g.developer, g.titleId, g.path]
        .filter(Boolean)
        .some((s) => String(s).toLowerCase().includes(q));
    });
  }, [games, query]);

  const launch = (game) => {
    if (!game?.path) return;
    if (status.firmwareOk === false) {
      setNote("Сначала прошивка: нажмите «Мост прошивки». Без bis/ Kenji зависает на Loading.");
      return;
    }
    const r = parse(call("launch", game.path, game.title || ""), { ok: true });
    setNote(r.message || "открываю официальный Kenji…");
  };

  return (
    <div className="app">
      <h1>Kenji Space</h1>
      <div className="sub">React-библиотека · игра идёт в официальном Kenji-NX</div>

      <div className="status">
        {(status.items || []).map((it) => (
          <div key={it.label}>
            <b>{it.label}</b>{" "}
            <span className={it.present ? "ok" : "bad"}>{it.present ? "есть" : "нет"}</span>
            {it.detail ? ` · ${it.detail}` : ""}
          </div>
        ))}
        {status.dataRoot ? <div>данные: {status.dataRoot}</div> : null}
        {!ready ? <div className="bad">откройте из APK — моста нет</div> : null}
      </div>

      <div className="row">
        <button className="primary" onClick={() => call("pickFolder")}>＋ Папка игр</button>
        <button onClick={() => call("pickDataRoot")}>Папка Eden / Kenji</button>
        <button onClick={() => setNote(parse(call("bridgeFirmware"), {}).message || "мост…")}>
          Мост прошивки
        </button>
        <button onClick={() => call("openOfficialHome")}>Их интерфейс</button>
      </div>

      <input
        className="search"
        placeholder="Найти игру…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />

      {visible.length === 0 ? (
        <div className="empty">
          {games.length ? "ничего не найдено" : "добавьте папку с NSP / XCI"}
        </div>
      ) : (
        <div className="list">
          {visible.map((game) => (
            <button key={game.path} className="game" onClick={() => launch(game)}>
              <div className="thumb">▶</div>
              <div className="meta">
                <div className="title">{game.title || "без имени"}</div>
                <div className="who">
                  {[game.fileSize, game.titleId].filter(Boolean).join(" · ")}
                </div>
              </div>
            </button>
          ))}
        </div>
      )}

      {note ? <div className="note">{note}</div> : null}
      <div className="note">
        Игра запускается официальным GameHost из Kenji-NX 2.1.0-pr.2. Эта страница только список.
      </div>
    </div>
  );
}
