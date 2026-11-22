/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';

import type { Rule } from '../../druid-models';
import { Capabilities } from '../../helpers';
import { getApiArray, queryDruidSql } from '../../utils';

import { RetentionDialog } from './retention-dialog';

jest.mock('../../utils', () => ({
  ...jest.requireActual('../../utils'),
  getApiArray: jest.fn(),
  queryDruidSql: jest.fn(),
}));

const DATASOURCE = 'test-datasource';

function renderDialog(rules: Rule[] = []) {
  const onSave = jest.fn();
  render(
    <RetentionDialog
      datasource={DATASOURCE}
      rules={rules}
      defaultRules={[{ tieredReplicants: { _default_tier: 2 }, type: 'loadForever' }]}
      capabilities={Capabilities.FULL}
      onEditDefaults={() => {}}
      onCancel={() => {}}
      onSave={onSave}
    />,
  );
  return onSave;
}

function saveNewRule() {
  fireEvent.click(screen.getByRole('button', { name: 'New rule' }));
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  fireEvent.change(screen.getByPlaceholderText('Enter description here'), {
    target: { value: 'Load it all' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
}

describe('RetentionDialog', () => {
  beforeEach(() => {
    jest.mocked(getApiArray).mockResolvedValue([]);
    jest
      .mocked(queryDruidSql)
      .mockReset()
      .mockResolvedValue([{ tier: '_default_tier' }]);
  });

  it('matches snapshot', async () => {
    renderDialog([
      {
        period: 'P1000Y',
        includeFuture: true,
        tieredReplicants: { _default_tier: 2 },
        type: 'loadByPeriod',
      },
    ]);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'New rule' })).toHaveProperty('disabled', false),
    );
    expect(document.body.lastChild).toMatchSnapshot();
  });

  it('waits for historical tiers before enabling New rule', async () => {
    const tiers = Promise.withResolvers<{ tier: string }[]>();
    jest.mocked(queryDruidSql).mockReturnValue(tiers.promise);
    const onSave = renderDialog();
    const newRule = screen.getByRole('button', { name: 'New rule' });

    expect(newRule).toHaveProperty('disabled', true);
    await waitFor(() => expect(queryDruidSql).toHaveBeenCalledTimes(1));
    expect(newRule).toHaveProperty('disabled', true);

    await act(async () => {
      tiers.resolve([{ tier: 'hot' }]);
      await tiers.promise;
    });
    expect(newRule).toHaveProperty('disabled', false);
    saveNewRule();
    expect(onSave).toHaveBeenCalledWith(
      DATASOURCE,
      [{ type: 'loadForever', tieredReplicants: { hot: 2 } }],
      'Load it all',
    );
  });

  it('uses the default tier when no historical tiers are returned', async () => {
    jest.mocked(queryDruidSql).mockResolvedValue([]);
    const onSave = renderDialog();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'New rule' })).toHaveProperty('disabled', false),
    );

    saveNewRule();
    expect(onSave).toHaveBeenCalledWith(
      DATASOURCE,
      [{ type: 'loadForever', tieredReplicants: { _default_tier: 2 } }],
      'Load it all',
    );
  });

  it('lets the user retry a failed tier lookup', async () => {
    jest.mocked(queryDruidSql).mockRejectedValueOnce(new Error('Tier query failed'));
    const onSave = renderDialog();
    await screen.findByText('Tier query failed');
    const newRule = screen.getByRole('button', { name: 'New rule' });
    expect(newRule).toHaveProperty('disabled', true);

    fireEvent.click(screen.getByRole('button', { name: 'Retry tier lookup' }));
    await waitFor(() => expect(newRule).toHaveProperty('disabled', false));
    expect(screen.queryByText('Tier query failed')).toBeNull();

    saveNewRule();
    expect(onSave).toHaveBeenCalledWith(
      DATASOURCE,
      [{ type: 'loadForever', tieredReplicants: { _default_tier: 2 } }],
      'Load it all',
    );
  });
});
