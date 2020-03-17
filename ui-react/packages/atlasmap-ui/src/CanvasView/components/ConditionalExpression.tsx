import React, { FunctionComponent } from 'react';
import { TextInput } from '@patternfly/react-core';

export interface IConditionalExpressionProps {
  expressionTokens: string[];
  condExprEnabled: boolean;
  onGetMappingExpressionStr: () => string;
}

export const ConditionalExpression: FunctionComponent<IConditionalExpressionProps> = ({
  // expressionTokens,
  condExprEnabled,
  onGetMappingExpressionStr,
}) => {
  return (
    <TextInput
      aria-label={'Conditional mapping expression'}
      // value={expressionTokens[0]}
      value={onGetMappingExpressionStr()}
      isDisabled={!condExprEnabled}
      // onChange={onExpressionChange}
    />
  );
};
