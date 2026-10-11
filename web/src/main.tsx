import React from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App.tsx'
import './ui/styles.css'

const root = document.getElementById('root')
if (root !== null) createRoot(root).render(<React.StrictMode><App /></React.StrictMode>)
