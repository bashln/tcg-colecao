import { defineConfig, devices } from '@playwright/test'

const chromiumLaunchOptions = process.env.CHROMIUM_EXECUTABLE_PATH === undefined
  ? undefined
  : { executablePath: process.env.CHROMIUM_EXECUTABLE_PATH }

export default defineConfig({
  testDir: './tests',
  use: {
    baseURL: 'http://127.0.0.1:4175',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], launchOptions: chromiumLaunchOptions },
    },
    {
      // O alvo do usuario sem iPhone e Safari. Rodar no CI revela a divergencia
      // antes do usuario encontrar.
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
  ],
  webServer: {
    command: 'VITE_SUPABASE_URL=https://project.supabase.test VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_playwright npm run dev -- --host 127.0.0.1 --port 4175',
    url: 'http://127.0.0.1:4175',
    reuseExistingServer: !process.env.CI,
  },
})
