import React from 'react';
import { Mapper } from '../src/mapper';
import { mappings, sources, targets } from './sampleData';

export default {
  title: 'Mapper',
};

export const sample = () => (
  <Mapper sources={sources} targets={targets} mappings={mappings} />
);