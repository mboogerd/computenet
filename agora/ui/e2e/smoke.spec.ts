import { test, expect } from '@playwright/test';

// Requires the agora backend running on :8080 (the Vite proxy targets it).
// Exercises the full path: boot → create a claim → it echoes back live over
// SSE → focus it → open its panel → set a stance that the UI reflects.
test('boot, create a claim live, open it, and set a stance', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByPlaceholder('New claim…')).toBeVisible();

  const claim = `Smoke claim ${Date.now()}`;
  await page.getByPlaceholder('New claim…').fill(claim);
  await page.getByRole('button', { name: 'Add', exact: true }).click();

  // the SSE echo makes it appear in the focal picker
  await expect(page.locator('.focal-picker option', { hasText: claim })).toHaveCount(1, {
    timeout: 8000,
  });

  await page.locator('.focal-picker').selectOption({ label: claim });
  const focal = page.locator('.debate__focal');
  await expect(focal).toContainText(claim);

  // open the shared detail panel and stake a device-local stance
  await focal.click();
  await expect(page.locator('.panel')).toBeVisible();
  await page.locator('.stance__range').fill('0.8');
  await expect(page.locator('.stance')).toContainText('Your stance: 0.80');
});
