import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { api, clearSession, getRole, getToken, saveSession } from "./api";
import "./styles.css";

function Login() {
  const [form, setForm] = useState({ username: "hr@company.com", password: "Password@123" });
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const session = await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify(form)
      });
      saveSession(session);
      window.location.reload();
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="login-page">
      <form className="login-card" onSubmit={submit}>
        <div className="brand-mark">W</div>
        <h1>Workforce Management</h1>
        <p>Sign in to continue</p>
        <label>Username
          <input value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} required />
        </label>
        <label>Password
          <input type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} required />
        </label>
        {error && <div className="alert error">{error}</div>}
        <button className="primary" disabled={loading}>{loading ? "Signing in..." : "Sign in"}</button>
      </form>
    </main>
  );
}

function Table({ columns, rows }) {
  if (!rows?.length) return <div className="empty">No records found.</div>;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{columns.map(c => <th key={c.key}>{c.label}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={row.id || index}>
              {columns.map(c => <td key={c.key}>{row[c.key] ?? "-"}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PageLoader({ children, onLoad }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    setError("");
    onLoad()
      .then(result => active && setData(result))
      .catch(err => active && setError(err.message));
    return () => { active = false; };
  }, [onLoad]);
  if (error) return <div className="alert error">{error}</div>;
  if (data === null) return <div className="loading">Loading...</div>;
  return children(data);
}

function Employees() {
  const role = getRole();
  const [search, setSearch] = useState("");
  const [reload, setReload] = useState(0);
  const load = React.useCallback(
    () => api(`/api/employees?search=${encodeURIComponent(search)}&size=100&sort=employeeCode&page=0&_=${reload}`),
    [search, reload]
  );

  return (
    <section>
      <div className="page-head">
        <div><p className="eyebrow">Organization</p><h1>Employees</h1></div>
        <div className="actions">
          <input className="search" placeholder="Search employees" value={search}
            onChange={e => setSearch(e.target.value)} />
          {role === "HR_ADMIN" && <button onClick={() => setReload(v => v + 1)}>Refresh</button>}
        </div>
      </div>
      <PageLoader onLoad={load}>
        {data => <Table
          columns={[
            ["employee_code", "Employee ID"],
            ["first_name", "First name"],
            ["last_name", "Last name"],
            ["email", "Email"],
            ["employment_status", "Status"]
          ].map(([key, label]) => ({ key, label }))}
          rows={data.content}
        />}
      </PageLoader>
    </section>
  );
}

function Departments() {
  return (
    <section>
      <div className="page-head"><div><p className="eyebrow">Organization</p><h1>Departments</h1></div></div>
      <PageLoader onLoad={() => Promise.all([api("/api/departments"), api("/api/departments/tree")])}>
        {([departments, tree]) => (
          <>
            <Table columns={[
              { key: "code", label: "Code" }, { key: "name", label: "Name" },
              { key: "active_employees", label: "Active employees" }
            ]} rows={departments} />
            <h2>Department hierarchy</h2>
            <div className="tree">
              {tree.map(row => <div key={row.id} style={{ marginLeft: (row.depth - 1) * 24 }}>
                {row.code} - {row.name}
              </div>)}
            </div>
          </>
        )}
      </PageLoader>
    </section>
  );
}

function Positions() {
  return (
    <section>
      <div className="page-head"><div><p className="eyebrow">Organization</p><h1>Positions</h1></div></div>
      <PageLoader onLoad={() => api("/api/positions")}>
        {positions => <Table columns={[
          { key: "code", label: "Code" }, { key: "title", label: "Title" },
          { key: "department_name", label: "Department" },
          { key: "active_employee_name", label: "Assigned employee" }
        ]} rows={positions} />}
      </PageLoader>
    </section>
  );
}

function Leaves() {
  const role = getRole();
  const [types, setTypes] = useState([]);
  const [requests, setRequests] = useState([]);
  const [balances, setBalances] = useState([]);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [form, setForm] = useState({ leaveTypeId: "", startDate: "", endDate: "", reason: "" });
  const [reload, setReload] = useState(0);

  useEffect(() => {
    Promise.all([
      api("/api/leaves/types"),
      api("/api/leaves"),
      api("/api/leaves/balances")
    ]).then(([t, r, b]) => {
      setTypes(t); setRequests(r); setBalances(b);
      if (t[0]) setForm(v => ({ ...v, leaveTypeId: String(t[0].id) }));
    }).catch(err => setError(err.message));
  }, [reload]);

  async function apply(event) {
    event.preventDefault();
    setError(""); setMessage("");
    try {
      await api("/api/leaves", {
        method: "POST",
        body: JSON.stringify({ ...form, leaveTypeId: Number(form.leaveTypeId) })
      });
      setMessage("Leave request submitted.");
      setReload(v => v + 1);
    } catch (err) { setError(err.message); }
  }

  async function action(id, action) {
    const comment = action === "REJECTED" ? window.prompt("Rejection comment") : "";
    if (action === "REJECTED" && !comment) return;
    try {
      await api(`/api/leaves/${id}/action`, { method: "POST", body: JSON.stringify({ action, comment }) });
      setReload(v => v + 1);
    } catch (err) { setError(err.message); }
  }

  return (
    <section>
      <div className="page-head"><div><p className="eyebrow">Time away</p><h1>Leave</h1></div></div>
      {error && <div className="alert error">{error}</div>}
      {message && <div className="alert success">{message}</div>}
      <div className="balance-grid">
        {balances.map(b => (
          <div className="metric" key={b.code}>
            <span>{b.name}</span><strong>{b.available_days}</strong><small>days available</small>
          </div>
        ))}
      </div>
      <div className="leave-layout">
        <form className="panel" onSubmit={apply}>
          <h2>Apply for leave</h2>
          <label>Leave type
            <select value={form.leaveTypeId} onChange={e => setForm({ ...form, leaveTypeId: e.target.value })}>
              {types.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </label>
          <div className="two-cols">
            <label>Start date<input type="date" value={form.startDate} onChange={e => setForm({ ...form, startDate: e.target.value })} required /></label>
            <label>End date<input type="date" value={form.endDate} onChange={e => setForm({ ...form, endDate: e.target.value })} required /></label>
          </div>
          <label>Reason<textarea value={form.reason} onChange={e => setForm({ ...form, reason: e.target.value })} required /></label>
          <button className="primary" type="submit">Submit request</button>
        </form>
        <div className="panel">
          <h2>Requests</h2>
          {requests.map(request => (
            <article className="request" key={request.id}>
              <div>
                <strong>{request.leave_type_code}</strong>
                <span>{request.start_date} to {request.end_date}</span>
              </div>
              <span className={`status ${String(request.status).toLowerCase()}`}>{request.status}</span>
              {role !== "EMPLOYEE" && request.status === "PENDING" && (
                <div className="request-actions">
                  <button className="approve" onClick={() => action(request.id, "APPROVED")}>Approve</button>
                  <button className="reject" onClick={() => action(request.id, "REJECTED")}>Reject</button>
                </div>
              )}
            </article>
          ))}
          {!requests.length && <div className="empty">No leave requests.</div>}
        </div>
      </div>
    </section>
  );
}

function Imports() {
  const [jobs, setJobs] = useState(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  async function load() {
    try { setJobs(await api("/api/imports")); }
    catch (err) { setError(err.message); }
  }
  useEffect(() => { load(); const timer = setInterval(load, 3000); return () => clearInterval(timer); }, []);

  async function upload(event) {
    event.preventDefault();
    const file = event.target.file.files[0];
    if (!file) return;
    setError(""); setMessage("");
    try {
      const form = new FormData();
      form.append("file", file);
      await api("/api/imports", { method: "POST", body: form });
      setMessage("Import queued.");
      load();
    } catch (err) { setError(err.message); }
  }

  return (
    <section>
      <div className="page-head"><div><p className="eyebrow">Data</p><h1>Bulk imports</h1></div></div>
      {error && <div className="alert error">{error}</div>}
      {message && <div className="alert success">{message}</div>}
      <form className="panel import-panel" onSubmit={upload}>
        <label>CSV file<input name="file" type="file" accept=".csv,text/csv" required /></label>
        <button className="primary">Upload and start</button>
      </form>
      {jobs === null ? <div className="loading">Loading...</div> :
        <Table columns={[
          { key: "file_name", label: "File" }, { key: "status", label: "Status" },
          { key: "total_rows", label: "Total" }, { key: "success_rows", label: "Success" },
          { key: "failed_rows", label: "Failed" }
        ]} rows={jobs} />}
    </section>
  );
}

function Workflow() {
  return (
    <section>
      <div className="page-head"><div><p className="eyebrow">Configuration</p><h1>Approval workflow</h1></div></div>
      <PageLoader onLoad={() => api("/api/workflows")}>
        {workflows => <Table columns={[
          { key: "id", label: "ID" }, { key: "name", label: "Name" },
          { key: "active", label: "Active" }
        ]} rows={workflows} />}
      </PageLoader>
    </section>
  );
}

function App() {
  const role = getRole();
  const [view, setView] = useState(
    role === "HR_ADMIN" ? "employees" : role === "MANAGER" ? "employees" : "employees"
  );

  if (!getToken()) return <Login />;

  const nav = role === "HR_ADMIN"
    ? [["employees", "Employees"], ["departments", "Departments"], ["positions", "Positions"], ["leaves", "Leaves"], ["imports", "Imports"], ["workflow", "Workflow"]]
    : role === "MANAGER"
      ? [["employees", "Team"], ["leaves", "Leaves"]]
      : [["employees", "My profile"], ["leaves", "Leave"]];

  const views = { employees: Employees, departments: Departments, positions: Positions, leaves: Leaves, imports: Imports, workflow: Workflow };
  const View = views[view];

  return (
    <div className="app">
      <aside>
        <div className="sidebar-brand"><span>W</span><strong>Workforce</strong></div>
        <nav>
          {nav.map(([key, label]) => (
            <button key={key} className={view === key ? "active" : ""} onClick={() => setView(key)}>{label}</button>
          ))}
        </nav>
      </aside>
      <div className="workspace">
        <header>
          <span className="role-badge">{role?.replace("_", " ")}</span>
          <button onClick={() => { clearSession(); window.location.reload(); }}>Logout</button>
        </header>
        <main><View /></main>
      </div>
    </div>
  );
}

createRoot(document.getElementById("root")).render(<App />);
