export const PASSWORD_HINT = 'Use de 8 a 128 caracteres, com maiúscula, minúscula, número e símbolo.';
export const PASSWORD_PATTERN = String.raw`(?=.*\p{Lu})(?=.*\p{Ll})(?=.*\p{Nd})(?=.*[\p{P}\p{S}])[\s\S]{8,128}`;
