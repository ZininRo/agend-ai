import type { ButtonHTMLAttributes } from 'react';
import styles from './GradientButton.module.css';

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  /** sm — 52px (кнопки в поле ввода), lg — 234px (большой микрофон) */
  size?: 'sm' | 'lg';
};

/** Круглая кнопка с фирменным градиентом. Внутрь кладётся иконка. */
export function GradientButton({ size = 'sm', className, ...rest }: Props) {
  const classes = [styles.button, styles[size], className].filter(Boolean).join(' ');
  return <button type="button" className={classes} {...rest} />;
}
