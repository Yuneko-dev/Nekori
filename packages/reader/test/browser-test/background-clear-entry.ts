import { registerStyleCommand } from '../../src/styles/reader.ts';

(window as Window & { registerReaderStyles?: typeof registerStyleCommand }).registerReaderStyles = registerStyleCommand;
