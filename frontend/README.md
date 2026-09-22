# LedgerFlow frontend

The browser interface is a React and TypeScript app built with Vite. During local development, Vite forwards `/api` requests to the backend at `http://127.0.0.1:18080`.

## Run locally

Start the LedgerFlow backend first, then open a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://127.0.0.1:5173`. Use `npm run build` to check the production bundle and `npm run lint` to run the frontend linter.
