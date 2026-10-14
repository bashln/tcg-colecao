import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  use: {
    baseURL: 'http://127.0.0.1:4175',
    ...devices['Desktop Chrome'],
    launchOptions: process.env.CHROMIUM_EXECUTABLE_PATH === undefined
      ? undefined
      : { executablePath: process.env.CHROMIUM_EXECUTABLE_PATH },
  },
  webServer: {
    command: 'VITE_SUPABASE_URL=https://project.supabase.test VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_playwright npm run dev -- --host 127.0.0.1 --port 4175',
    url: 'http://127.0.0.1:4175',
    reuseExistingServer: !process.env.CI,
  },
})
