// SPDX-License-Identifier: GPL-3.0-or-later
use crate::args::*;
use clap::Subcommand;
#[derive(Subcommand)]
pub(crate) enum EqCommand {
    List,
    Preset {
        id: Option<u8>,
    },
    /// Create a custom EQ; six gains: 62,250,1000,4000,8000,16000 Hz, -6..6 dB.
    Create {
        name: String,
        #[arg(num_args = 6, allow_hyphen_values = true, value_parser = clap::value_parser!(i8).range(-6..=6))]
        gains: Vec<i8>,
    },
    Update {
        id: u8,
        name: String,
        #[arg(num_args = 6, allow_hyphen_values = true, value_parser = clap::value_parser!(i8).range(-6..=6))]
        gains: Vec<i8>,
    },
    Select {
        id: u8,
    },
    Rename {
        id: u8,
        name: String,
    },
    Delete {
        id: u8,
        #[arg(long)]
        confirm: bool,
    },
}
impl EqCommand {
    pub fn command(self) -> Command {
        match self {
            Self::List => Command::EqList,
            Self::Preset { id } => Command::EqPreset { id },
            Self::Create { name, gains } => Command::EqSave {
                id: 0,
                name,
                gains,
                add: true,
            },
            Self::Update { id, name, gains } => Command::EqSave {
                id,
                name,
                gains,
                add: false,
            },
            Self::Select { id } => Command::EqSelect { id },
            Self::Rename { id, name } => Command::EqRename { id, name },
            Self::Delete { id, confirm } => Command::EqDelete { id, confirm },
        }
    }
}
