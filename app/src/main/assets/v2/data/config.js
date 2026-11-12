/* Supabase connection. The publishable key is designed for browsers.
   Access to data is controlled by Supabase row-level security, not by this file. */
window.KB_SUPABASE = {
  url: 'https://ggewdpazpeoielbqrcgm.supabase.co',
  key: 'sb_publishable_RZYBKLqjC9UYLNdqlOKZCA_IFX-5Gvm'
};

/* Owner settings. Only this account sees the owner tabs (the database rules still decide what it can read).
   Later, with multi-user accounts, these move into each user's own settings. */
window.KB_CONFIG = {
  ownerEmail: 'haleannson@gmail.com',
  // Default weather place. Can be changed in Settings → Owner settings.
  weather: { name: 'Leonberg', lat: 48.80, lon: 9.02 },
  // Owner tabs that can be switched on or off in Settings.
  modules: [
    { key: 'today', label: 'Today (weather, briefing, homework)' },
    { key: 'timetable', label: 'School (WebUntis)' },
    { key: 'admin', label: 'Admin (examples, reviews, questions, requests)' },
  ],
};

/* Colours for school subjects. Matched by keyword (case-insensitive), so "Mathe", "Mathematik A-Kurs" both become maths.
   Colours can be changed per subject in Settings. */
window.KB_SUBJECT_COLORS = [
  { match: ['mathe', 'math'], label: 'Maths', color: '#2563eb' },
  { match: ['englisch', 'english'], label: 'English', color: '#eab308' },
  { match: ['deutsch', 'german'], label: 'German', color: '#dc2626' },
  { match: ['spanisch', 'spanish', 'fremdsprache'], label: 'Spanish', color: '#f97316' },
  { match: ['physik', 'physics'], label: 'Physics', color: '#7c3aed' },
  { match: ['chemie', 'chemistry'], label: 'Chemistry', color: '#16a34a' },
  { match: ['biologie', 'biology', 'bio'], label: 'Biology', color: '#22c55e' },
  { match: ['gl', 'gesellschaft', 'geschichte', 'history'], label: 'Society', color: '#a16207' },
  { match: ['religion', 'ethik'], label: 'Religion / Ethics', color: '#64748b' },
  { match: ['sport'], label: 'Sport', color: '#0891b2' },
  { match: ['musik', 'bläser', 'blaser', 'music', 'ensemble'], label: 'Music', color: '#db2777' },
];
